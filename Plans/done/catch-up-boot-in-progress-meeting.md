# Catch-up: Boot While a Meeting Is In Progress

## Problem

`scheduleEventsForToday()` only schedules checks that are **in the future**.  
If the backend restarts while a class session is running (`start ≤ now < end`), the
`SESSION_START` task is skipped, so no polling ever begins. The monitor simply waits for
`SESSION_FINALIZE` with no live participant data during the remaining window.

---

## Design

### 1. Detection in `scheduleEventsForToday()`

After computing the four `Instant` values for an event, add an in-progress check:

```java
boolean inProgress = !start.isAfter(now) && end.isAfter(now);
if (inProgress) {
    resumeSessionPolling(event);
}
```

This runs **in addition to** scheduling `SESSION_FINALIZE` (which is still needed so
`finalizeSession` fires at the true end time).

---

### 2. New method: `resumeSessionPolling(CalendarEvent event)`

Mirrors `startSessionPolling` with two key differences:

**a) Pre-seed `seenStudentIds` / `seenTeacherIds` from the database**

Query existing `Attendance` records for `(calendarEventId, date=today)` and add any
already-recorded student/teacher IDs into the seen-sets before the first poll.
This ensures:
- The "all present" early-exit check is correct from the first tick.
- We never attempt to re-notify for participants recorded before the restart (though
  `NotificationLog` deduplication would also prevent double notifications, pre-seeding
  avoids the unnecessary attempt).

```java
Set<Long> seenStudentIds = new HashSet<>();
Set<Long> seenTeacherIds = new HashSet<>();

// Pre-seed from existing DB records
attendanceRepository.findByCalendarEventIdAndDate(event.getId(), LocalDate.now())
    .forEach(a -> {
        if (a.getStudent() != null) seenStudentIds.add(a.getStudent().getId());
        if (a.getTeacher() != null) seenTeacherIds.add(a.getTeacher().getId());
    });
```

**b) `lateThreshold` is anchored to class start, not "now"**

Same formula as `startSessionPolling` — `classStart + lateBufferMinutes`. Participants
seen during catch-up polling who joined after the threshold are still correctly marked LATE.

**c) No "meeting not started" reminder branch**

`startSessionPolling` contains logic that fires `sendMeetingStartReminder` if the meeting
isn't active yet. In catch-up mode we skip this: if Meet isn't active mid-session, we just
wait for the next polling tick (or `finalizeSession` will handle it via `getAllParticipants`).

**d) Immediate snapshot, then 60 s fixed-rate loop**

Identical to `startSessionPolling`:
1. One immediate participant snapshot.
2. `taskScheduler.scheduleAtFixedRate(…, Duration.ofSeconds(60))`.
3. Register the future in `pollingFutures` so `finalizeSession` can cancel it.

---

### 3. No changes required elsewhere

| Component | Why untouched |
|---|---|
| `finalizeSession` | Already guards with `existing.isEmpty()` before writing attendance; correctly handles any combination of pre-recorded and new absences |
| `processParticipants` | Already deduplicates via `seenStudentIds`; `recordStudentAttendance` has its own DB guard |
| `notificationService.notify` | `NotificationLog` key `(studentId, classId, date, type)` prevents double notifications even without pre-seeding |
| Stale-event cleanup in `scheduleEventsForToday` | Unaffected; cancellation logic targets `oneTimeFutures` and `pollingFutures` by event ID |

---

## Repository method needed

`AttendanceRepository` needs:

```java
List<Attendance> findByCalendarEventIdAndDate(String calendarEventId, LocalDate date);
```

Check whether this query already exists before adding it.

---

## Edge cases

| Scenario | Behaviour |
|---|---|
| Backend restarts after class ends (`now >= end`) | `end.isAfter(now)` is false → `resumeSessionPolling` not called; `finalizeSession` was already called before restart, so attendance is already in the DB |
| Meet isn't active when catch-up polling starts | Snapshot finds no participants → no-op; fixed-rate loop keeps checking; `finalizeSession` uses `getAllParticipants` history as the final authority |
| All participants already recorded before restart | Pre-seeded seen-sets satisfy the "all present" check on the first tick → polling loop self-cancels immediately |
| Class was cancelled (`ClassCancellation` record exists) | `getTodaysEvents()` / `calendarSyncService` should already exclude cancelled sessions; no change needed here |

---

## Implementation checklist

- [ ] Add `findByCalendarEventIdAndDate` to `AttendanceRepository` (if not already present)
- [ ] Add `resumeSessionPolling(CalendarEvent event)` to `MeetAttendanceMonitor`
- [ ] Call `resumeSessionPolling` from `scheduleEventsForToday` when `inProgress` is true
- [ ] Unit test: boot with one event in-progress → `resumeSessionPolling` called, polling future registered, `finalizeSession` still fires at end time
- [ ] Unit test: boot with event already ended → `resumeSessionPolling` NOT called
