# School Management App — High-Level Design

## Overview

A local, single-user school management tool for the principal. No authentication required. Runs on `localhost`, accessed via browser. Spring Boot backend with H2 database, React frontend.

---

## Backend

### Tech Stack

| Layer | Choice |
|---|---|
| Framework | Spring Boot (Java/Kotlin) |
| Database | H2 (embedded, file-persisted) |
| ORM | Spring Data JPA / Hibernate |
| Authentication | None |
| XLSX Export | Apache POI |
| Email | Spring Mail (Gmail SMTP) |
| SMS | Twilio API |
| Build | Maven or Gradle |

### Google Integration Setup

Uses a **Google Service Account** — configured once, no OAuth prompts.

```properties
# application.properties
google.credentials.path=./service-account.json
google.calendar.id=principal@school.com

spring.mail.host=smtp.gmail.com
spring.mail.username=school@gmail.com
spring.mail.password=...

twilio.account-sid=...
twilio.auth-token=...
twilio.from-number=+1...
```

Required Google API scopes:
- `https://www.googleapis.com/auth/classroom.courses`
- `https://www.googleapis.com/auth/classroom.rosters`
- `https://www.googleapis.com/auth/classroom.coursework.students`
- `https://www.googleapis.com/auth/calendar`
- `https://www.googleapis.com/auth/meetings.space.readonly`  ← Meet participant data

---

### Data Model

```
Term
  id, name, startDate, endDate

Student
  id, name, email, phone, parentEmail, parentPhone
  enrolledDate, active (soft delete)

Teacher
  id, name, email, hourlyRate

Class
  id, name, teacherId, termId
  schedule (day/time)
  googleMeetLink
  googleClassroomId

Enrollment
  studentId → classId (many-to-many)

Attendance
  id, studentId, classId, date
  status (PRESENT | ABSENT | LATE)

Assignment
  id, classId, sessionId
  googleCourseWorkId
  teacherPostedOnTime  (boolean — posted within 2 days of class date)
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
  id, recipientEmail, recipientPhone, type, message, sentAt, channel (EMAIL | SMS)
```

---

### API Endpoints

```
# Students
GET    /students
POST   /students
GET    /students/{id}
PUT    /students/{id}
DELETE /students/{id}          # soft delete (sets active = false)

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
POST   /classes                # Phase 1: stores googleClassroomId manually; Phase 2: also auto-creates Meet link + Classroom course
PUT    /classes/{id}
POST   /classes/{id}/roster-sync       # sync students from Classroom

# Attendance
POST   /attendance                  # bulk mark for a session
GET    /attendance/student/{id}     # full history

# Assignments & Submissions
POST   /assignments/sync/{classId}  # pull from Classroom, set compliance flags
GET    /assignments/class/{classId} # teacher posting compliance view
GET    /submissions/student/{id}    # student submission compliance view

# Payments
GET    /payments/outstanding        # all unpaid invoices, sorted by due date
POST   /payments/invoice            # create invoice for a student
POST   /payments/{invoiceId}/pay    # record a payment

# Payroll
GET    /payroll                     # all teachers + amount currently owed
POST   /payroll/session             # log a session for a teacher
POST   /payroll/{teacherId}/pay     # mark pay period as settled

# Notifications
GET    /notifications               # view notification log

# Export (XLSX)
GET    /export/students
GET    /export/attendance
GET    /export/submissions
GET    /export/payments
GET    /export/payroll
GET    /export/full                 # multi-sheet workbook (also auto-saves to /backups)
```

---

### Google Integrations

#### Google Meet (via Calendar API) — Phase 2
- `POST /classes` creates a Calendar event with a Meet link via `conferenceData`
- Recurring classes use `RRULE` recurrence on the Calendar event
- Meet link is stored on the `Class` record

#### Live Meet Attendance Monitor (via Meet REST API) — Phase 1
- A Spring `@Scheduled` task runs every ~60 seconds
- Each tick identifies classes currently within their scheduled session window
- For each active session, calls `spaces/{spaceId}/participants` (filtered by `latestEndTime IS NULL`) on the Meet REST API to list participants currently in the call
- Each participant is matched to an enrolled student by Google account email
- `PRESENT` is written when a student's first join event is seen; `LATE` if their `earliestStartTime` is beyond the configured grace period past class start
- At session end (class end time + a short buffer), any enrolled student still without an `Attendance` row is written as `ABSENT`
- Notifications fire once per student per session — deduplication prevents repeat emails across poller ticks

#### Google Classroom — Read (Phase 1) / Provision (Phase 2)
- **Phase 1 (read-only):** `googleClassroomId` is set manually on the Class record; the app never creates or modifies Classroom courses
  - `POST /classes/{id}/roster-sync` pulls enrolled students via `courses.students.list`
  - `POST /assignments/sync/{classId}` pulls coursework and submissions:
    - `teacherPostedOnTime` — coursework `creationTime` within 2 days of class date
    - `submittedOnTime` — derived from Classroom's native `late` field on `studentSubmissions`
- **Phase 2 (publish):** `POST /classes` additionally provisions a Classroom course and assigns the teacher

---

### Notifications

#### Email & SMS Triggers

| Trigger | Recipient | Channel | Phase |
|---|---|---|---|
| Student absent (detected by live monitor at session end) | Parent | Email | 1 |
| Student late (detected by live monitor on delayed join) | Parent + Principal | Email | 1 |
| Invoice overdue | Principal + Parent | Email + SMS | 4 |
| Payment received | Principal | Email | 4 |
| Teacher didn't post homework on time | Principal | Email | 4 |
| Student missed submission deadline | Principal + Parent | Email | 4 |
| Teacher payroll due | Principal | Email | 4 |

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
│  Export     │                                  │
│             │                                  │
└─────────────┴──────────────────────────────────┘
```

Single page app with persistent sidebar navigation. One React Router route per section.

---

### Pages

#### Dashboard (Default View)

Two focal points on load:

**Ongoing Classes**
- Table: Class Name, Teacher, Time, Meet Link (clickable), Students Enrolled
- Status badge: `IN SESSION` / `UPCOMING` / `DONE` based on current time

**Today's Attendance**
- Per-class summary: e.g. `18 / 20 present`
- Click a class → per-student breakdown (Present / Absent / Late)
- Mark attendance inline from this view

**Alerts Panel**
- Proactive issue feed, e.g.:
  - "3 invoices overdue"
  - "Teacher X hasn't posted homework for Class Y"
  - "2 students absent 3+ times this month"

#### Students
- Searchable table: name, email, enrolled classes, outstanding balance
- Click a student → detail panel: attendance history, submission compliance, invoices
- Inactive (soft-deleted) students hidden by default, toggleable

#### Teachers
- Table: name, hourly rate, amount currently owed
- Click a teacher → session log, payroll history

#### Classes
- Table: class name, teacher, term, schedule, Meet link, Classroom link
- Create class → form that triggers backend (auto-creates Meet + Classroom course)
- Per-class view: roster, assignment compliance, sync button

#### Payments
- Default view: outstanding invoices sorted by due date
- Color coding: overdue = red, due soon = amber, paid = grey
- Actions: create invoice, mark as paid

#### Payroll
- Table: teacher, hours this period, rate, total owed, paid status
- Log session button
- Mark paid button per teacher

#### Export
- One button per export type
- "Export All" button for the full multi-sheet workbook
- Each triggers a file download from the backend

---

## Desktop Shortcut

A `start.bat` (Windows) / `start.sh` (Mac/Linux) script that:
1. Starts the Spring Boot JAR (`java -jar school-app.jar`)
2. Opens `http://localhost:3000` in the default browser

The principal double-clicks the shortcut and the app is ready. No installation beyond Java and the JAR file.

---

## Build Phases

### Phase 1 — Core Data, Classroom Sync & Attendance Notifications
- Spring Boot project setup, H2, JPA entities (all 11 entities including `NotificationLog`)
- Basic CRUD endpoints for all core entities
- Soft delete on students
- React app + sidebar layout + routing
- Students, Teachers, Classes pages (read/create/edit); Class form includes manual `googleClassroomId` field
- **Google Classroom (read-only):** roster sync + assignment/submission pull with `teacherPostedOnTime` / `submittedOnTime` flags
- **Live Meet attendance monitor:** `@Scheduled` poller queries Admin Reports API during active sessions; writes `PRESENT`/`LATE` during the session and `ABSENT` at session end
- **Spring Mail setup:** email notifications fired by the monitor — absent → parent; late → parent + principal (once per student per session)
- NotificationLog viewer in frontend

### Phase 2 — Dashboard & Google Publishing
- Dashboard: ongoing classes, today's attendance view, alerts panel
- Attendance manual mark UI
- Google Calendar/Meet link generation on class creation
- Google Classroom course provisioning on class creation (publish side)

### Phase 3 — Payments & Payroll
- Invoice creation, payment recording, overdue status
- Teacher session logging + payroll calculation
- Payments and Payroll pages in frontend

### Phase 4 — Remaining Notifications
- Twilio SMS setup
- Remaining notification triggers: invoice overdue (email + SMS), payment received, teacher late homework, missed submission, payroll due

### Phase 5 — Export & Polish
- Apache POI XLSX export for all entities
- Export All + auto-backup to `/backups`
- `start.bat` / `start.sh` desktop shortcut scripts
- General UI polish, edge cases, error handling
