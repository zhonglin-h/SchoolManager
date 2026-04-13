# Unify `notify` via a `Recipient` Abstraction

## Problem

`NotificationService` has two separate notification paths:

| Path | Method | Person | Dedup | Log |
|---|---|---|---|---|
| Structured | `notify(NotificationType, CalendarEvent, Student)` | Student or null | Per channel via `NotificationLog` | Yes |
| Raw | `notifyTelegram(String, CalendarEvent, String)` | None (student-null key) | Event-level only | Yes, but no person FK |

**All teacher notifications** (TEACHER_ABSENT, TEACHER_LATE, TEACHER_ARRIVED) go through the raw
path. This means:
- Teacher types are raw strings, not enum values — no compile-time safety
- Teacher message bodies are built inline at the call site, not centralized
- Teacher notifications deduplicate as if they were event-level (student-null key), not per-teacher
- `NotificationType` lambdas are `BiFunction<CalendarEvent, Student, String>` — Student is hardcoded

The goal is a **single** `notify(NotificationType, CalendarEvent, Recipient)` entry point that
handles students, teachers, and null (event-level) uniformly.

---

## Design

### 1. `Recipient` sealed interface — new file

Package: `com.school.service`

```java
public sealed interface Recipient permits StudentRecipient, TeacherRecipient {
    Long getId();
    String getName();
}
```

```java
public record StudentRecipient(Student student) implements Recipient {
    public Long getId()   { return student.getId(); }
    public String getName() { return student.getName(); }
}
```

```java
public record TeacherRecipient(Teacher teacher) implements Recipient {
    public Long getId()   { return teacher.getId(); }
    public String getName() { return teacher.getName(); }
}
```

`notify` continues to accept `null` for event-level notifications (MEETING_NOT_STARTED_15,
ALL_PRESENT) — no change to those call sites.

---

### 2. `NotificationType` — add teacher entries, widen lambda type

**Change lambda type** from `BiFunction<CalendarEvent, Student, String>` to
`BiFunction<CalendarEvent, Recipient, String>`.

Existing student-specific lambdas that call `s.getName()` continue to work because `Recipient`
exposes `getName()`. Lambdas that receive null (event-level types) already ignore the second
argument.

**Add three new enum entries** (currently raw strings in `MeetAttendanceMonitor`):

```java
TEACHER_ARRIVED(
    (e, r) -> "Teacher Arrived: " + r.getName(),
    (e, r) -> r.getName() + " has joined the Meet session for \"" + e.getTitle() + "\".",
    false, true, false
),
TEACHER_LATE(
    (e, r) -> "Teacher Late: " + r.getName(),
    (e, r) -> r.getName() + " joined the Meet session late for \"" + e.getTitle() + "\".",
    false, true, false
),
TEACHER_ABSENT(
    (e, r) -> "Teacher Absent: " + r.getName(),
    (e, r) -> r.getName() + " was absent from the Meet session for \"" + e.getTitle() + "\".",
    false, true, false
);
```

All three are Telegram-only to the principal (`toPrincipalViaTelegram = true`).
`toParentViaEmail` remains `false` — the routing logic in `notify` already gates that flag on
`recipient instanceof StudentRecipient`.

---

### 3. `NotificationLog` — add `teacher` FK

```java
@ManyToOne(optional = true)
private Teacher teacher;
```

The `student` field stays. For any given log row, exactly one of `student`, `teacher`, or both
will be null:

| Row kind | `student` | `teacher` |
|---|---|---|
| Student notification | set | null |
| Teacher notification | null | set |
| Event-level notification | null | null |

---

### 4. `NotificationLogRepository` — add teacher-keyed dedup query

```java
boolean existsByTeacherIdAndCalendarEventIdAndDateAndTypeAndChannelAndSuccessTrue(
    Long teacherId, String calendarEventId, LocalDate date, String type, NotificationChannel channel);
```

The existing student-null query (`existsByCalendarEventIdAndDateAndTypeAndChannelAndStudentIsNullAndSuccessTrue`)
continues to serve event-level dedup. The student-keyed queries are unchanged.

---

### 5. `NotificationService.notify` — new signature, unified routing

```java
public void notify(NotificationType type, CalendarEvent event, @Nullable Recipient recipient)
```

**Email path** (unchanged logic, widened types):
- `toPrincipalViaEmail` → send to principal email as before
- `toParentViaEmail && recipient instanceof StudentRecipient sr` → send to `sr.student().getParentEmail()`
- Teachers do not have a parent email; the flag is simply skipped for `TeacherRecipient`

**Telegram path** (unchanged logic):
- `toPrincipalViaTelegram` → send to principal chat as before

**Dedup selection** in each channel block:
```java
if (recipient instanceof StudentRecipient sr) {
    alreadySent = repo.existsByStudentId...(..., sr.getId(), ...);
} else if (recipient instanceof TeacherRecipient tr) {
    alreadySent = repo.existsByTeacherId...(..., tr.getId(), ...);
} else {
    alreadySent = repo.existsByCalendarEventIdAndDate...StudentIsNull...(...);
}
```

**`NotificationLog` builder** sets either `student(...)` or `teacher(...)` based on recipient type.

**`notifyTelegram` is deleted** — all callers are migrated to `notify`.

---

### 6. `MeetAttendanceMonitor` — migrate three call sites

Replace each `notifyTelegram` call with `notify` + `TeacherRecipient`:

```java
// Before
notificationService.notifyTelegram("TEACHER_ABSENT", event,
        teacher.getName() + " was absent...");

// After
notificationService.notify(NotificationType.TEACHER_ABSENT, event,
        new TeacherRecipient(teacher));
```

The inline message strings are deleted — message generation moves into `NotificationType`.

---

## Files changed

| File | Change |
|---|---|
| `Recipient.java` *(new)* | Sealed interface |
| `StudentRecipient.java` *(new)* | Record implementing `Recipient` |
| `TeacherRecipient.java` *(new)* | Record implementing `Recipient` |
| `NotificationType.java` | Widen lambda type; add TEACHER_ARRIVED, TEACHER_LATE, TEACHER_ABSENT |
| `NotificationLog.java` | Add `teacher` FK field |
| `NotificationLogRepository.java` | Add teacher-keyed dedup query |
| `NotificationService.java` | Widen `notify` signature; unified routing; delete `notifyTelegram` |
| `MeetAttendanceMonitor.java` | Replace three `notifyTelegram` calls with `notify(…, TeacherRecipient)` |

No other service or controller calls `notifyTelegram` — the delete is safe once those three
call sites are migrated.

---

## What does NOT change

- `Student` and `Teacher` JPA entities — untouched
- `NotificationLog.student` FK — untouched
- Event-level call sites (`notify(type, event, null)`) — untouched
- Student call sites (`notify(type, event, student)`) — only wrap: `new StudentRecipient(student)`
- `clearTodayLogsForEvent` and `isNotificationsEnabled` — untouched

---

## Implementation checklist

- [ ] Create `Recipient`, `StudentRecipient`, `TeacherRecipient`
- [ ] Add `teacher` FK to `NotificationLog`
- [ ] Add teacher-keyed dedup query to `NotificationLogRepository`
- [ ] Widen `NotificationType` lambdas; add TEACHER_ARRIVED / TEACHER_LATE / TEACHER_ABSENT
- [ ] Rewrite `NotificationService.notify` to accept `Recipient`; delete `notifyTelegram`
- [ ] Migrate three `notifyTelegram` call sites in `MeetAttendanceMonitor`
- [ ] Wrap existing student call sites with `new StudentRecipient(student)`
- [ ] Verify no remaining references to `notifyTelegram` or the three raw type-key strings
