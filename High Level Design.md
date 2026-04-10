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
POST   /classes                # auto-creates Meet link + Classroom course
PUT    /classes/{id}
POST   /classes/{id}/roster-sync   # sync students from Classroom

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

#### Google Meet (via Calendar API)
- `POST /classes` creates a Calendar event with a Meet link via `conferenceData`
- Recurring classes use `RRULE` recurrence on the Calendar event
- Meet link is stored on the `Class` record

#### Google Classroom (via Classroom API)
- `POST /classes` provisions a Classroom course and assigns the teacher
- `POST /classes/{id}/roster-sync` pulls enrolled students via `courses.students.list`
- `POST /assignments/sync/{classId}` pulls coursework and submissions:
  - `teacherPostedOnTime` — coursework `creationTime` within 2 days of class date
  - `submittedOnTime` — derived from Classroom's native `late` field on `studentSubmissions`

---

### Notifications

#### Email & SMS Triggers

| Trigger | Recipient | Channel |
|---|---|---|
| Invoice overdue | Principal + Parent | Email + SMS |
| Payment received | Principal | Email |
| Teacher didn't post homework on time | Principal | Email |
| Student missed submission deadline | Principal + Parent | Email |
| Teacher payroll due | Principal | Email |
| Student absent | Parent | SMS |

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

### Phase 1 — Core Data & Basic UI
- Spring Boot project setup, H2, JPA entities (Student, Teacher, Class, Enrollment, Term)
- Basic CRUD endpoints for all core entities
- Soft delete on students
- React app + sidebar layout + routing
- Students, Teachers, Classes pages (read/create/edit)

### Phase 2 — Attendance & Submissions
- Attendance tracking + mark attendance from dashboard
- Google Classroom + Meet integration (course creation, Meet link generation)
- Assignment sync + `teacherPostedOnTime` / `submittedOnTime` flags
- Dashboard: ongoing classes + today's attendance view

### Phase 3 — Payments & Payroll
- Invoice creation, payment recording, overdue status
- Teacher session logging + payroll calculation
- Payments and Payroll pages in frontend
- Dashboard alerts panel

### Phase 4 — Notifications
- Spring Mail + Twilio setup
- Notification triggers (overdue invoices, absences, missed submissions, payroll due)
- NotificationLog entity + log viewer in frontend

### Phase 5 — Export & Polish
- Apache POI XLSX export for all entities
- Export All + auto-backup to `/backups`
- `start.bat` / `start.sh` desktop shortcut scripts
- General UI polish, edge cases, error handling
