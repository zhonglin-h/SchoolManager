# MeetAttendanceMonitor — Behavior Reference

## Scheduling

- **On startup** (`ApplicationReadyEvent`) and **at midnight daily** (`@Scheduled cron`): calls `scheduleEventsForToday()`.
- `scheduleEventsForToday()` fetches today's calendar events and cancels any previously-scheduled futures for events that are no longer on today's calendar.
- If an event's start time changed since the last sync, today's notification logs for that event are cleared so notifications can re-fire at the correct time.

---

## Per-Event Tasks Scheduled

For each event today, four one-shot tasks are registered (skipped if their trigger time has already passed):

| Trigger | Task |
|---------|------|
| T − 15 min | `MEETING_NOT_STARTED_15` check |
| T − 3 min | `PRE_CLASS_JOINS` check |
| Class start | `SESSION_START` — begins live polling |
| Class end | `SESSION_FINALIZE` — final attendance sweep |

If the app starts while a session is already in progress (start ≤ now < end), `resumeSessionPolling` is called immediately instead of scheduling `SESSION_START`.

---

## T − 15 min: Meeting-Not-Started Check

Calls `isMeetingActive` on the Meet space.  
If the meeting is **not** active → fires `MEETING_NOT_STARTED_15` notification.

---

## T − 3 min: Pre-Class Join Check

Fetches current active participants and resolves them to student/teacher IDs.  
For each expected student/teacher/guest **not yet present** → fires `NOT_YET_JOINED_3` notification.

---

## Session Start Polling

1. Takes an immediate participant snapshot at class start.
   - If meeting not active: sends a `MEETING_NOT_STARTED_15` reminder and continues.
2. Starts a **60-second fixed-rate polling loop**.
   - Each tick:
     - If meeting still not active: calls `isMeetingActive`; if still down, sends another start reminder and skips to next tick.
     - Once active: processes the current participant list (see below).
     - If **all** expected students and teachers have been seen → fires `ALL_PRESENT` and stops the loop.

---

## Session Resume (Catch-up on Startup)

- Pre-seeds the "seen" sets from attendance records already in the database.
- Takes an immediate participant snapshot.
- Starts the same 60-second polling loop as above (no meeting-active check; assumes it's live).

---

## processParticipants (each polling tick)

For each expected student or teacher newly seen in the snapshot (not already in the "seen" set):

- check status in attendance. If no records for this meeting, check following

- **Student**
  - `now ≤ lateThreshold` → status `PRESENT`, notify `ARRIVAL`. Add record to attendance.
  - `now > lateThreshold` → status `LATE`, notify `LATE`. Add record to attendance.
- **Teacher**
  - `now ≤ lateThreshold` → status `PRESENT`, notify `TEACHER_ARRIVED`. Add record to attendance.
  - `now > lateThreshold` → status `LATE`, notify `TEACHER_LATE`. Add record to attendance.

`lateThreshold = classStart + lateBufferMinutes` (configured via `app.attendance.late-buffer-minutes`).

Attendance is only written once per person per event per day (idempotent DB check).

---

## Session Finalize (at class end)

1. Stops the polling loop if it's still running.
2. Fetches the **full participant history** (including people who already left) from the Meet API.
3. For each expected student or teacher with **no attendance record yet**:
   - Join time not found → `ABSENT` + notify `ABSENT` / `TEACHER_ABSENT`
   - Join time after late threshold → `LATE` + notify `LATE` / `TEACHER_LATE`
   - Join time on time → `PRESENT` + notify `ARRIVAL` / `TEACHER_ARRIVED`

---

## Participant Resolution & Auto-Learning

`resolveAndAutoLearn` maps Meet participants to DB entities using this priority:

1. `googleUserId` (exact match)
2. `meetDisplayName` (case-insensitive)
3. `name` (case-insensitive fallback)

Students are checked before teachers. On first match, any missing `googleUserId` or `meetDisplayName` is saved back to the entity automatically ("auto-learn").

Expected participants are determined from the event's attendee email list, matched against active students and teachers by `meetEmail`.
