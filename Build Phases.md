# School Management App — Build Phases

Incremental delivery plan. Each phase produces a working, runnable state of the app. See `High Level Design.md` for the final target design.

---

## Phase 1 — Student Registry, Calendar-Driven Attendance & Notifications

**Goal:** The app monitors today's Google Meet classes and notifies parents/principal automatically.

**Backend:**
- Spring Boot project setup, H2, JPA entities: `Student`, `Attendance`, `NotificationLog`
  - Phase 1 `Attendance` and `NotificationLog` use `calendarEventId` (string) as the session identifier in place of `classId`, since local `Class` entities don't exist yet; Phase 2 migrates these to `classId + date`
- Student CRUD endpoints + soft delete
- Google Calendar integration (read-only): fetch today's events at startup and midnight; extract attendees, start/end times, Meet link, spaceId
- Live Meet attendance monitor via Spring `TaskScheduler`:
  - T−15 min: check if meeting started; notify principal if not
  - T−3 min: snapshot present students; notify principal of students/teachers that hasn't joined; send parent notifications
  - T+0: snapshot present students and teachers; begin per-minute polling. Send message to principal if student arrived, and when everyone's there.
  - Per-minute: mark late joiners; stop when all expected students and teachers accounted for
  - T+end + buffer: mark remaining students `ABSENT`; send parent notifications
- Roster matched from Calendar attendees to local `Student` by `meetEmail`
- Spring Mail: email notifications
- `POST /calendar/sync` for on-demand calendar refresh
- `GET /attendance/today`, `GET /attendance/student/{id}`, `GET /notifications`

**Frontend:**
- React app with sidebar layout and routing
- Students page: table, add/edit/soft-delete
- Dashboard: today's attendance summary (per-event, expandable to per-student)
- Notifications page: log of sent notifications

**Google API scopes needed:** `calendar.readonly`, `meetings.space.readonly`

---

## Phase 2 — Classes & Full Calendar Integration

**Goal:** Local class records linked to Google Calendar events; full class management UI.

**Backend:**
- `Term`, `Teacher`, `Class`, `ClassCancellation`, `Enrollment` entities
- Class registration: link a local `Class` to an existing Google Calendar event; read `RRULE`, start/end, Meet link from the event
- Nightly exception sync (`events.instances`) → `ClassCancellation`; on-demand `POST /classes/{id}/calendar-sync`
- Attendance poller updated to skip `ClassCancellation` dates and use local `Class` schedule
- Teacher and Term CRUD endpoints; Class CRUD endpoints

**Frontend:**
- Classes page: table, link-to-Calendar form, per-class view with "Sync Calendar" button
- Teachers page: table, session log, payroll history stub
- Dashboard: Ongoing Classes section (IN SESSION / UPCOMING / DONE badges); Alerts Panel stub

**Google API scopes added:** `calendar` (full, for reading event details and future event creation)

---

## Phase 3 — Google Classroom Integration

**Goal:** Roster and assignment compliance pulled from Google Classroom.

**Backend:**
- `googleClassroomId` field on `Class`; `Assignment`, `StudentSubmission` entities
- `POST /classes/{id}/roster-sync` — pull enrolled students via `courses.students.list`; match by `classroomEmail`
- `POST /assignments/sync/{classId}` — pull coursework + submissions; compute `teacherPostedOnTime` / `submittedOnTime`
- `GET /assignments/class/{classId}`, `GET /submissions/student/{id}`

**Frontend:**
- Classes per-class view: roster tab, assignment compliance tab, "Sync Roster" button
- Student detail panel: submission compliance section

**Google API scopes added:** `classroom.courses`, `classroom.rosters`, `classroom.coursework.students`

---

## Phase 4 — Payments & Payroll

**Goal:** Invoice tracking and teacher payroll.

**Backend:**
- `Invoice`, `Payment`, `SessionLog` entities
- `GET /payments/outstanding`, `POST /payments/invoice`, `POST /payments/{invoiceId}/pay`
- `GET /payroll`, `POST /payroll/session`, `POST /payroll/{teacherId}/pay`

**Frontend:**
- Payments page: outstanding invoices, color-coded by status, create/pay actions
- Payroll page: teacher table, log session, mark paid

---

## Phase 5 — Remaining Notifications

**Goal:** SMS support and remaining automated notification triggers.

**Backend:**
- Twilio SMS integration
- Scheduled/event-driven triggers:
  - Invoice overdue → Principal + Parent (Email + SMS)
  - Payment received → Principal (Email)
  - Teacher didn't post homework on time → Principal (Email)
  - Student missed submission deadline → Principal + Parent (Email)
  - Teacher payroll due → Principal (Email)

---

## Phase 6 — Export & Polish

**Goal:** Data export, desktop launcher, and final polish.

**Backend:**
- Apache POI XLSX export: `/export/students`, `/export/attendance`, `/export/submissions`, `/export/payments`, `/export/payroll`
- `/export/full` — multi-sheet workbook; auto-saves timestamped copy to `/backups`

**Frontend:**
- Export page: one button per export type + "Export All"
- General UI polish, empty states, error handling

**Other:**
- `start.bat` (Windows) / `start.sh` (Mac/Linux) desktop launcher scripts
