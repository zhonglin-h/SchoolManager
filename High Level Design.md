# School Management App — High-Level Design

## Overview

A local, single-user school management tool for the principal. No authentication required. Runs on `localhost`, accessed via browser. Spring Boot backend with H2 database, React frontend.

---

## Backend

### Tech Stack

| Layer | Choice |
|---|---|
| Framework | Spring Boot (Java) |
| Database | H2 (embedded, file-persisted) |
| ORM | Spring Data JPA / Hibernate |
| Authentication | None |
| XLSX Export | Apache POI |
| Email | Spring Mail (Gmail SMTP) |
| SMS | Twilio API |
| Build | Maven |

### Google Integration Setup

Uses a **Google Service Account** — configured once, no OAuth prompts.

```properties
# application.properties
google.credentials.path=./service-account.json
google.calendar.id=principal@school.com

app.principal.email=principal@school.com

spring.mail.host=smtp.gmail.com
spring.mail.username=school@gmail.com
spring.mail.password=...

twilio.account-sid=...
twilio.auth-token=...
twilio.from-number=+1...
```

Required Google API scopes:

| Scope | Purpose |
|---|---|
| `calendar.readonly` | Read today's events, attendees, Meet links |
| `calendar` (full) | Create recurring events + Meet links |
| `meetings.space.readonly` | Meet participant data |
| `classroom.courses` | Classroom course access |
| `classroom.rosters` | Enrollment sync |
| `classroom.coursework.students` | Assignment/submission sync |

---

### Data Model

```
Student
  id, name, meetEmail, classroomEmail, parentEmail, parentPhone
  active (soft delete)
  ← meetEmail: matched against Calendar attendees + Meet participants
  ← classroomEmail: matched against Classroom roster; may differ from meetEmail

Term
  id, name, startDate, endDate

Teacher
  id, name, email, meetEmail, hourlyRate
  ← meetEmail: Google account used to join Meet; matched against Calendar attendees + Meet participants

Class
  id, name, teacherId, termId
  scheduleDays       ← e.g. "MON,WED" — read from Calendar RRULE BYDAY
  startTime          ← from Calendar event start
  endTime            ← from Calendar event end
  calendarEventId    ← base recurring event ID; used for exception sync
  googleMeetLink
  googleClassroomId

ClassCancellation
  id, classId, date  ← populated nightly by Calendar sync; poller skips these dates

Enrollment
  studentId → classId (many-to-many)

Attendance
  id, studentId, classId, date
  status (PRESENT | ABSENT | LATE)
  ← date identifies which session within the recurring class

Assignment
  id, classId, classDate
  googleCourseWorkId
  teacherPostedOnTime  (boolean — creationTime within 2 days of classDate)
  dueDate

StudentSubmission
  id, assignmentId, studentId
  submittedOnTime  (boolean — from Classroom's native late flag)

Invoice
  id, studentId, termId, amount, dueDate
  status (UNPAID | PAID | OVERDUE)

Payment
  id, invoiceId, amountPaid, paidDate, method

SessionLog
  id, teacherId, classId, date, durationMinutes

NotificationLog
  id, studentId, classId, date, type, message, sentAt, channel (EMAIL | SMS)
  ← (studentId, classId, date, type) is the deduplication key
  ← studentId nullable for principal-only notifications (e.g. meeting not started)
```

---

### API Endpoints

```
# Students
GET    /students
POST   /students
GET    /students/{id}
PUT    /students/{id}
DELETE /students/{id}                  # soft delete (sets active = false)

# Teachers
GET    /teachers
POST   /teachers
PUT    /teachers/{id}
DELETE /teachers/{id}

# Terms
GET    /terms
POST   /terms
PUT    /terms/{id}

# Classes
GET    /classes
POST   /classes                        # links to existing Calendar event; reads schedule + Meet link from it
PUT    /classes/{id}
POST   /classes/{id}/calendar-sync     # on-demand exception sync for this class
POST   /classes/{id}/roster-sync       # sync enrolled students from Classroom

# Attendance
GET    /attendance/today               # today's sessions with per-session attendance summary
GET    /attendance/student/{id}        # student's full attendance history

# Assignments & Submissions
POST   /assignments/sync/{classId}     # pull from Classroom, compute compliance flags
GET    /assignments/class/{classId}    # teacher posting compliance view
GET    /submissions/student/{id}       # student submission compliance view

# Payments
GET    /payments/outstanding           # all unpaid invoices, sorted by due date
POST   /payments/invoice              # create invoice for a student
POST   /payments/{invoiceId}/pay      # record a payment

# Payroll
GET    /payroll                        # all teachers + amount currently owed
POST   /payroll/session               # log a session for a teacher
POST   /payroll/{teacherId}/pay       # mark pay period as settled

# Notifications
GET    /notifications                  # notification log

# Calendar
POST   /calendar/sync                  # manually re-fetch today's events

# Export (XLSX)
GET    /export/students
GET    /export/attendance
GET    /export/submissions
GET    /export/payments
GET    /export/payroll
GET    /export/full                    # multi-sheet workbook (also auto-saves to /backups)
```

---

### Google Integrations

#### Google Calendar

- Principal manages class schedules in Google Calendar (source of truth for recurrence and exceptions)
- When a class is registered in the app, the app reads the Calendar event's `RRULE` `BYDAY`, `start`, and `end` to populate local `Class` schedule fields; stores `calendarEventId` and Meet link from `conferenceData`
- **Exception sync:** a nightly `@Scheduled` job calls `events.instances` for each Class's `calendarEventId` and writes cancelled instances to `ClassCancellation`
- `POST /calendar/sync` and `POST /classes/{id}/calendar-sync` allow on-demand re-fetch (e.g. for last-minute cancellations)

#### Live Meet Attendance Monitor

At startup and daily at midnight, fetches today's Calendar events. For each event with a Meet link, Spring `TaskScheduler` schedules the following tasks:

| Time | Action |
|---|---|
| T − 15 min | Call `spaces/{spaceId}/participants`; if no active participants, notify principal "Meeting not started" |
| T − 3 min | Snapshot who is present; notify principal of any students or teacher not yet joined; notify those students' parents |
| T + 0 (start) | Snapshot present students and teacher; begin per-minute polling; notify principal as each new arrival joins, and once when everyone is present |
| T + 0 → T + end | Poll every minute; mark each new joiner `LATE`; stop early once all expected students and teacher have been seen |
| T + end + buffer | Mark all still-absent students `ABSENT`; send parent notifications; stop polling |

- Roster = Calendar event `attendees`, matched to local `Student` records by `meetEmail` and local `Teacher` records by `meetEmail`
- `PRESENT` on a student's or teacher's first join at or before T + 0; `LATE` on any join detected after T + 0
- Notifications fire once per student per session — `NotificationLog` keyed on `(studentId, classId, date, type)` prevents duplicate sends
- Principal notifications (meeting not started, arrivals, all-present) are keyed on `(classId, date, type)` with `studentId` nullable

#### Google Classroom

- `googleClassroomId` is set manually on the local `Class` record
- `POST /classes/{id}/roster-sync` pulls enrolled students via `courses.students.list`; matches by `classroomEmail`
- `POST /assignments/sync/{classId}` pulls coursework and submissions:
  - `teacherPostedOnTime` — coursework `creationTime` within 2 days of `classDate`
  - `submittedOnTime` — derived from Classroom's native `late` field on `studentSubmissions`

---

### Notifications

| Trigger | Recipient | Channel |
|---|---|---|
| Meeting not started at T−15 min | Principal | Email |
| Student / teacher not yet joined at T−3 min | Principal + Parent (per student) | Email |
| Student or teacher arrived (after T+0) | Principal | Email |
| All students and teacher present | Principal | Email |
| Student absent (detected at session end) | Parent | Email |
| Student late (detected on delayed join) | Parent + Principal | Email |
| Invoice overdue | Principal + Parent | Email + SMS |
| Payment received | Principal | Email |
| Teacher didn't post homework on time | Principal | Email |
| Student missed submission deadline | Principal + Parent | Email |
| Teacher payroll due | Principal | Email |

All sent notifications are recorded in `NotificationLog`.

---

### Export & Backup

- Each `/export/*` endpoint streams a `.xlsx` file using Apache POI
- `/export/full` generates a multi-sheet workbook and auto-saves a timestamped copy to `/backups`
- Acts as a lightweight backup strategy for the embedded H2 database

---

## Frontend

### Tech Stack

| Concern | Choice |
|---|---|
| Framework | React + Vite |
| UI Components | shadcn/ui |
| Data Fetching | TanStack Query |
| Routing | React Router |
| Tables | TanStack Table |
| State | TanStack Query (server state) + useState (UI state) |

Communicates with Spring Boot at `http://localhost:8080`.

---

### Layout

```
┌─────────────┬──────────────────────────────────┐
│             │                                  │
│  Dashboard  │         Main Content             │
│  Students   │                                  │
│  Teachers   │                                  │
│  Classes    │                                  │
│  Payments   │                                  │
│  Payroll    │                                  │
│  Notifications                                 │
│  Export     │                                  │
│             │                                  │
└─────────────┴──────────────────────────────────┘
```

Single page app with persistent sidebar navigation. One React Router route per section.

---

### Pages

#### Dashboard
- **Ongoing Classes:** table of today's classes (from Calendar) with status badge `IN SESSION` / `UPCOMING` / `DONE`; Meet link clickable
- **Today's Attendance:** per-class summary (e.g. `18 / 20 present`); click to expand per-student breakdown
- **Alerts Panel:** proactive issue feed — overdue invoices, late homework, repeated absences

#### Students
- Searchable table: name, email, parent contact, enrolled classes, outstanding balance
- Click a student → detail panel: attendance history, submission compliance, invoices
- Inactive students hidden by default, toggleable

#### Teachers
- Table: name, hourly rate, amount currently owed
- Click a teacher → session log, payroll history

#### Classes
- Table: class name, teacher, term, schedule, Meet link, Classroom link
- Link class to an existing Google Calendar event
- Per-class view: roster, assignment compliance, "Sync Calendar" and "Sync Roster" buttons

#### Payments
- Default view: outstanding invoices sorted by due date
- Color coding: overdue = red, due soon = amber, paid = grey
- Actions: create invoice, mark as paid

#### Payroll
- Table: teacher, hours this period, rate, total owed, paid status
- Log session button; mark paid button per teacher

#### Notifications
- Log of all sent notifications: recipient, type, channel, timestamp

#### Export
- One button per export type; "Export All" for the full multi-sheet workbook

---

## Desktop Shortcut

A `start.bat` (Windows) / `start.sh` (Mac/Linux) script that:
1. Starts the Spring Boot JAR (`java -jar school-app.jar`)
2. Opens `http://localhost:3000` in the default browser

The principal double-clicks the shortcut and the app is ready. No installation beyond Java and the JAR file.
