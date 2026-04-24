# Plan: Unmatched Guest Notifications + Polling Row in Upcoming Checks

## Context

Two related gaps in the live Meet monitoring:

1. **Unmatched guests** — two categories of people the principal hasn't set up accounts for:
   - **Unmatched invitees**: emails on the calendar event's attendee list that have no student or teacher record with a matching `meetEmail` in the DB
   - **Unmatched active participants**: people currently in the Meet room whose display name / Google ID can't be resolved to any expected student or teacher
   
   Both are combined into one notification per polling trigger.

2. **Missing polling row** — the continuous 60-second polling loop has no entry in `GET /calendar/scheduled-checks`. The four one-time tasks (T-15, T-3, start, finalize) all appear, but once polling is live nothing represents it.

---

## Feature 1: Unmatched Guest Notifications

### New Notification Type

**File:** [service/NotificationType.java](backend/src/main/java/com/school/service/NotificationType.java)

Add:
```java
UNMATCHED_GUESTS(
    "Unknown People in Session: {title}",
    // body built dynamically — see below
    /*toPrincipalViaEmail=*/ true,
    /*toPrincipalViaTelegram=*/ true,
    /*toParentViaEmail=*/ false
)
```

Dynamic body example:
```
Invited but not found in system: alice@gmail.com, bob@gmail.com
In room but not found in system: Charlie, Unknown User
```
Only sections that are non-empty are included.

### New Helper Methods in MeetAttendanceHelper

**File:** [service/MeetAttendanceHelper.java](backend/src/main/java/com/school/service/MeetAttendanceHelper.java)

**`findUnmatchedInvitees(CalendarEvent event)`**
- Takes `event.getAttendeeEmails()`
- For each email, checks if any student has `meetEmail` matching it, or if the teacher has `meetEmail` matching it (using the same student/teacher repositories already injected)
- Returns `List<String>` of emails that matched nothing in the DB
- This is a static check (calendar attendees don't change mid-session)

**`findUnmatchedParticipants(List<MeetParticipant> participants, ExpectedParticipants expected)`**
- For each `MeetParticipant`, tries to match against `expected.students()` and `expected.teacher()` using the existing priority chain (`googleUserId` → `meetDisplayName` → `name`)
- Returns `List<String>` of display names for participants that matched nothing
- Skips participants with blank/null display names

### No Deduplication

Notify on **every polling trigger** (T-3 and each 60-second tick) if either unmatched list is non-empty. No local set tracking, no DB dedup. The principal receives a notification on each tick until the unmatched people are either accounted for or leave.

### Sending the Notification

**File:** [service/NotificationService.java](backend/src/main/java/com/school/service/NotificationService.java)

Add:
```java
public void sendUnmatchedGuestsNotification(
    List<String> unmatchedInvitees,   // emails
    List<String> unmatchedParticipants, // display names
    CalendarEvent event)
```
- Builds body combining both lists (omit a section if its list is empty)
- Sends email + Telegram to principal
- Persists `NotificationLog` with `student = null`, `teacher = null`
- Does **not** call `dedupCheck()`

### Integration in MeetSessionHandler

**File:** [service/MeetSessionHandler.java](backend/src/main/java/com/school/service/MeetSessionHandler.java)

**`checkPreClassJoins(CalendarEvent event)` (T-3):**
```java
List<String> unmatchedInvitees = attendanceHelper.findUnmatchedInvitees(event);
List<MeetParticipant> activeParticipants = meetClient.getActiveParticipants(event.getSpaceCode());
List<String> unmatchedParticipants = attendanceHelper.findUnmatchedParticipants(activeParticipants, expected);
if (!unmatchedInvitees.isEmpty() || !unmatchedParticipants.isEmpty()) {
    notificationService.sendUnmatchedGuestsNotification(unmatchedInvitees, unmatchedParticipants, event);
}
```

**`schedulePollingLoop(...)` (each 60-second tick):**
- `unmatchedInvitees` is fetched once before the loop starts (static — calendar attendees don't change) and captured in the loop closure
- On each tick, call `attendanceHelper.findUnmatchedParticipants(activeParticipants, expected)` with the current participant snapshot
- If either list is non-empty, call `notificationService.sendUnmatchedGuestsNotification(...)`

---

## Feature 2: SESSION_POLLING Row in Upcoming Checks

### Problem

The polling loop is started inside `MeetSessionHandler`, but `upcomingChecks` lives in `MeetAttendanceMonitor`. Removing the `SESSION_POLLING` entry the moment the loop self-cancels (e.g., when `ALL_PRESENT` fires) requires `MeetSessionHandler` to reach back into `MeetAttendanceMonitor` — creating a circular dependency.

### Solution: Extract `UpcomingChecksRegistry`

**New file:** [service/UpcomingChecksRegistry.java](backend/src/main/java/com/school/service/UpcomingChecksRegistry.java)

```java
@Component
public class UpcomingChecksRegistry {
    private final CopyOnWriteArrayList<ScheduledCheck> checks = new CopyOnWriteArrayList<>();

    public void add(ScheduledCheck check) { checks.add(check); }

    public void removePollingEntry(String eventId) {
        checks.removeIf(c -> c.eventId().equals(eventId)
                          && "SESSION_POLLING".equals(c.checkType()));
    }

    public List<ScheduledCheck> getUpcoming() {
        return checks.stream()
            .filter(c -> c.scheduledAt().isAfter(Instant.now()))
            .sorted(Comparator.comparing(ScheduledCheck::scheduledAt))
            .collect(Collectors.toList());
    }
}
```

Both `MeetAttendanceMonitor` and `MeetSessionHandler` inject this bean — no circular dependency.

### Changes in MeetAttendanceMonitor

**File:** [service/MeetAttendanceMonitor.java](backend/src/main/java/com/school/service/MeetAttendanceMonitor.java)

- Move `upcomingChecks` list into `UpcomingChecksRegistry`; replace all direct list access with registry calls
- When calling `startSessionPolling` or `resumeSessionPolling`, add a `SESSION_POLLING` entry first:
  ```java
  upcomingChecksRegistry.add(new ScheduledCheck(
      event.getId(), event.getTitle(), "SESSION_POLLING",
      event.getEndTime().toInstant()));
  ```
- In `cancelOneTimeFutures(eventId)`, also call `upcomingChecksRegistry.removePollingEntry(eventId)`
- `getUpcomingChecks()` delegates to `upcomingChecksRegistry.getUpcoming()`

### Changes in MeetSessionHandler

**File:** [service/MeetSessionHandler.java](backend/src/main/java/com/school/service/MeetSessionHandler.java)

Inject `UpcomingChecksRegistry`. Call `upcomingChecksRegistry.removePollingEntry(event.getId())` inside `cancelPollingFor(eventId)` — this covers all termination paths:
- Loop self-cancels when all participants are seen
- `finalizeSession()` calls `cancelPollingFor()`
- External reschedule/removal calls `cancelPollingFor()` via `MeetAttendanceMonitor`

The `SESSION_POLLING` entry uses `scheduledAt = event.endTime` so it also naturally expires from the filtered view at class end, but the explicit removal in `cancelPollingFor` ensures it disappears immediately when the loop stops early.

---

## Files to Modify / Create

| File | Action |
|------|--------|
| [service/NotificationType.java](backend/src/main/java/com/school/service/NotificationType.java) | Add `UNMATCHED_GUESTS` |
| [service/MeetAttendanceHelper.java](backend/src/main/java/com/school/service/MeetAttendanceHelper.java) | Add `findUnmatchedInvitees()` and `findUnmatchedParticipants()` |
| [service/NotificationService.java](backend/src/main/java/com/school/service/NotificationService.java) | Add `sendUnmatchedGuestsNotification()` |
| [service/MeetSessionHandler.java](backend/src/main/java/com/school/service/MeetSessionHandler.java) | Call new helpers in T-3 and polling loop; inject registry; remove polling entry in `cancelPollingFor` |
| [service/UpcomingChecksRegistry.java](backend/src/main/java/com/school/service/UpcomingChecksRegistry.java) | **New** — shared registry bean |
| [service/MeetAttendanceMonitor.java](backend/src/main/java/com/school/service/MeetAttendanceMonitor.java) | Inject registry; add `SESSION_POLLING` on start/resume; remove on cancel; delegate `getUpcomingChecks()` |

---

## Verification

1. **Unmatched guest notification:**
   - Invite a test email not in the DB to a calendar event; confirm `findUnmatchedInvitees` returns it
   - Have an unknown Google account join the Meet room; confirm `findUnmatchedParticipants` returns their display name
   - Wait for T-3 or a polling tick — verify `NotificationLog` row with type `UNMATCHED_GUESTS`, null student/teacher
   - Confirm Telegram/email received; confirm a second tick sends another notification (no dedup)

2. **Polling row:**
   - Start a session; call `GET /calendar/scheduled-checks` — confirm `SESSION_POLLING` entry is present
   - Simulate all students joining (`ALL_PRESENT` fires, loop cancels) — call endpoint immediately after and confirm the entry is already gone, before class end
   - Also test: `POST /calendar/sync` during active polling → entry removed

3. **Run existing tests:**
   ```bash
   cd backend && ./gradlew test --tests "*.MeetSessionHandlerTest"
   ```
