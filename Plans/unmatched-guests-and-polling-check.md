# Plan: Unmatched Guest Notifications + Polling Row in Upcoming Checks

## Context

Two related gaps in the live Meet monitoring:

1. **Unmatched guests** — participants who join the Meet room but can't be resolved to any expected student or teacher. Currently silently ignored; the principal has no way to know someone unexpected is in the session.

2. **Missing polling row** — the continuous 60-second polling loop (`SESSION_POLLING`) has no entry in `GET /calendar/scheduled-checks`. The four one-time tasks (T-15, T-3, start, finalize) all appear, but once the polling loop is live there's nothing to show it's running.

---

## Feature 1: Unmatched Guest Notifications

### Definition

An **unmatched guest** is a `MeetParticipant` returned by the Meet API who does not resolve to any of the _expected_ participants for that class session (enrolled students + assigned teacher). This covers anyone the principal has not yet mapped to a student or teacher account.

### New Notification Type

**File:** [backend/src/main/java/com/school/service/NotificationType.java](backend/src/main/java/com/school/service/NotificationType.java)

Add:
```java
UNMATCHED_GUEST(
    "Unknown Guest in Meet: {title}",
    "{name} is in the Meet session for \"{title}\" but has no student or teacher account.",
    /*toPrincipalViaEmail=*/ true,
    /*toPrincipalViaTelegram=*/ true,
    /*toParentViaEmail=*/ false
)
```

### Surfacing Unresolved Participants

**File:** [backend/src/main/java/com/school/service/MeetAttendanceHelper.java](backend/src/main/java/com/school/service/MeetAttendanceHelper.java)

Add a new method:
```java
public Set<String> findUnmatchedGuests(
    List<MeetParticipant> participants,
    ExpectedParticipants expected)
```
- Iterates over all `participants`
- For each, tries to match against `expected.students()` and `expected.teacher()` using the same priority chain (googleUserId → meetDisplayName → name) as `resolveAndAutoLearn()`
- Returns the set of `displayName` values for participants that matched nothing
- Exclude participants with a blank/null display name (anonymous users with no identity)

### Deduplication Strategy

Use a **local `Set<String> seenUnmatchedGuests`** (keyed by `displayName`) that lives for the lifetime of each session — analogous to `seenStudentIds` / `seenTeacherIds`. This avoids any DB schema change and fires exactly once per unique guest per session.

### Sending the Notification

**File:** [backend/src/main/java/com/school/service/NotificationService.java](backend/src/main/java/com/school/service/NotificationService.java)

Add:
```java
public void sendUnmatchedGuestNotification(String guestDisplayName, CalendarEvent event)
```
- Builds subject/body from `UNMATCHED_GUEST` template, substituting `{name}` = `guestDisplayName` and `{title}` = event title
- Sends email to principal if `toPrincipalViaEmail`
- Sends Telegram to principal if `toPrincipalViaTelegram`
- Persists a `NotificationLog` with `student = null`, `teacher = null`, `success = true/false`
- Does **not** call `dedupCheck()` — dedup is handled by the caller's local set

### Integration in MeetSessionHandler

**File:** [backend/src/main/java/com/school/service/MeetSessionHandler.java](backend/src/main/java/com/school/service/MeetSessionHandler.java)

**`checkPreClassJoins(CalendarEvent event)` (T-3 check):**
- After the existing NOT_YET_JOINED logic, call `attendanceHelper.findUnmatchedGuests(activeParticipants, expected)`
- For each returned guest name, call `notificationService.sendUnmatchedGuestNotification(name, event)`
- No local set needed here — T-3 runs once per session

**`schedulePollingLoop(...)` (60-second loop):**
- Add `Set<String> seenUnmatchedGuests = new HashSet<>()` alongside `seenStudentIds` / `seenTeacherIds`
- On each tick, after processing expected participants, call `attendanceHelper.findUnmatchedGuests(activeParticipants, expected)`
- For each name not already in `seenUnmatchedGuests`:
  - Call `notificationService.sendUnmatchedGuestNotification(name, event)`
  - Add name to `seenUnmatchedGuests`

---

## Feature 2: SESSION_POLLING Row in Upcoming Checks

### Current State

`MeetAttendanceMonitor` maintains:
- `upcomingChecks: CopyOnWriteArrayList<ScheduledCheck>` — one-time task entries
- `getUpcomingChecks()` filters to entries where `scheduledAt.isAfter(now())`

The 60-second polling loop is started by `MeetSessionHandler` and is invisible to `upcomingChecks`.

### Approach

Represent the active polling loop as an entry with:
- `checkType = "SESSION_POLLING"`
- `scheduledAt = event.getEndTime().toInstant()` — so it naturally expires from the filtered list when class ends, consistent with existing filter logic

**File:** [backend/src/main/java/com/school/service/MeetAttendanceMonitor.java](backend/src/main/java/com/school/service/MeetAttendanceMonitor.java)

**Add a `SESSION_POLLING` entry** in the wrapper lambda that calls `startSessionPolling` / `resumeSessionPolling`:
```java
// Before calling sessionHandler.startSessionPolling(event):
upcomingChecks.add(new ScheduledCheck(event.getId(), event.getTitle(),
    "SESSION_POLLING", event.getEndTime().toInstant()));

sessionHandler.startSessionPolling(event);
```

**Remove the entry** in two places:
1. In the `SESSION_FINALIZE` wrapper lambda, after `sessionHandler.finalizeSession(event)`:
   ```java
   upcomingChecks.removeIf(c ->
       c.eventId().equals(event.getId()) && "SESSION_POLLING".equals(c.checkType()));
   ```
2. In `cancelOneTimeFutures(String eventId)` (already called for reschedules and event removal):
   ```java
   upcomingChecks.removeIf(c ->
       c.eventId().equals(eventId) && "SESSION_POLLING".equals(c.checkType()));
   ```

Do the same for `resumeSessionPolling` on startup.

No changes needed to `getUpcomingChecks()` — the `isAfter(now)` filter already handles it correctly since `scheduledAt = endTime`.

---

## Files to Modify

| File | Change |
|------|--------|
| [service/NotificationType.java](backend/src/main/java/com/school/service/NotificationType.java) | Add `UNMATCHED_GUEST` enum constant |
| [service/MeetAttendanceHelper.java](backend/src/main/java/com/school/service/MeetAttendanceHelper.java) | Add `findUnmatchedGuests()` method |
| [service/NotificationService.java](backend/src/main/java/com/school/service/NotificationService.java) | Add `sendUnmatchedGuestNotification()` method |
| [service/MeetSessionHandler.java](backend/src/main/java/com/school/service/MeetSessionHandler.java) | Use `findUnmatchedGuests` in T-3 check and polling loop; add `seenUnmatchedGuests` set |
| [service/MeetAttendanceMonitor.java](backend/src/main/java/com/school/service/MeetAttendanceMonitor.java) | Add/remove `SESSION_POLLING` entries in `upcomingChecks` around `startSessionPolling`, `resumeSessionPolling`, `finalizeSession`, and `cancelOneTimeFutures` |

---

## Verification

1. **Unmatched guest notification:**
   - Use `GET /attendance/debug/{spaceCode}` to confirm a test participant with no account appears in raw Meet data
   - Trigger `checkPreClassJoins` manually (or wait for T-3) and verify a `NotificationLog` row appears with type `UNMATCHED_GUEST` and null student/teacher
   - Verify Telegram/email is received by principal
   - Join again from same account — confirm no duplicate notification fires (local set dedup)

2. **Polling row:**
   - Start a session (or use `resumeSessionPolling` on startup during an active class)
   - Call `GET /calendar/scheduled-checks` — confirm a `SESSION_POLLING` entry appears for the active event
   - Wait for class end (or trigger `finalizeSession`) — call endpoint again and confirm entry is gone
   - Trigger a calendar reschedule (`POST /calendar/sync`) during an active session — confirm entry is removed via `cancelOneTimeFutures`

3. **Run existing tests:**
   ```bash
   cd backend && ./gradlew test --tests "*.MeetSessionHandlerTest"
   ```
