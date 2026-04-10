# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

School Manager is a local, single-user full-stack web application for a school principal to manage students, teachers, classes, attendance, payments, and payroll. No authentication is required — it runs entirely on `localhost`. The design is documented in `High Level Design.md`.

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Spring Boot (Java), Maven |
| Database | H2 (embedded, file-persisted at `./data/schooldb`) |
| ORM | Spring Data JPA / Hibernate |
| Frontend | React 18 + TypeScript + Vite |
| UI Components | shadcn/ui |
| Data Fetching | TanStack Query |
| Routing | React Router |
| Tables | TanStack Table |
| Exports | Apache POI (XLSX) |
| Email | Spring Mail (Gmail SMTP) |
| SMS | Twilio API |
| Google APIs | Service Account (Classroom + Calendar) |

## Commands

### Backend (once scaffolded)
```bash
cd backend
mvn spring-boot:run        # Start on :8080
mvn clean install          # Build
mvn test                   # Run all tests
mvn test -Dtest=ClassName  # Run a single test class
```

### Frontend (once scaffolded)
```bash
cd frontend
npm install
npm run dev        # Start Vite dev server on :3000
npm run build      # Production build
npm run lint       # ESLint
```

### Full stack
```bash
# Terminal 1
cd backend && mvn spring-boot:run

# Terminal 2
cd frontend && npm run dev
# Visit http://localhost:3000
```

## Architecture

### Directory Structure (planned)
```
backend/src/main/java/com/school/
  controller/      # REST endpoints
  service/         # Business logic
  repository/      # Spring Data JPA repositories
  entity/          # JPA entities
  dto/             # Request/response DTOs
  config/          # Spring configuration beans
  integration/     # Google, Twilio, Gmail clients
  SchoolManagerApp.java

frontend/src/
  components/      # Reusable React components
  pages/           # Route-level page components
  services/        # API call functions (axios/fetch)
  hooks/           # Custom React hooks (TanStack Query wrappers)
```

### Data Model (11 entities)
- **Term** — academic periods
- **Student** — soft-deleted via `active` flag
- **Teacher** — includes `hourlyRate`
- **Class** — links to Teacher and Term; holds `googleMeetLink` and `googleClassroomId`
- **Enrollment** — many-to-many Student ↔ Class
- **Attendance** — per student per class session (`PRESENT` / `ABSENT` / `LATE`)
- **Assignment** — synced from Google Classroom; has `teacherPostedOnTime` flag (within 2 days of class date)
- **StudentSubmission** — synced from Classroom; has `submittedOnTime` flag
- **Invoice** — student billing (`UNPAID` / `PAID` / `OVERDUE`)
- **Payment** — records individual payments against invoices
- **SessionLog** — teacher work sessions for payroll (duration in minutes)
- **NotificationLog** — immutable audit trail of every notification sent

### API Design
- Backend exposes a REST API at `http://localhost:8080`
- Frontend uses `VITE_API_BASE_URL=http://localhost:8080`
- All responses use standard HTTP status codes; errors return a JSON body with `message`

### Google API Integration
- Uses a **Service Account** (credentials at `./service-account.json`, never committed)
- **Google Classroom**: Phase 1 read-only (sync enrollments, pull assignments/submissions); Phase 2 adds course provisioning
- **Google Meet**: live attendance monitor via Meet REST API — `@Scheduled` poller queries `spaces/{spaceId}/participants` during active sessions
- **Google Calendar**: Phase 2 — create recurring events and generate Meet links stored on the Class record

### Notification Triggers
Automated notifications are logged to `NotificationLog`. See `High Level Design.md` for the full trigger table and phase breakdown.

### Runtime Directories
- `./data/` — H2 database files (created at runtime, not committed)
- `./backups/` — timestamped XLSX backups auto-saved on export (not committed)

## Configuration

Backend `application.properties` requires:
```properties
spring.datasource.url=jdbc:h2:file:./data/schooldb
google.credentials.path=./service-account.json
spring.mail.username=<gmail address>
spring.mail.password=<app password>
twilio.account-sid=<sid>
twilio.auth-token=<token>
twilio.from-number=<number>
```

Frontend `.env.local`:
```
VITE_API_BASE_URL=http://localhost:8080
```

Credential files (`service-account.json`, `.env.local`) must never be committed.

## Development Phases

1. **Phase 1** — Core CRUD + Classroom read-only sync + live Meet attendance monitor + absent/late email notifications
2. **Phase 2** — Dashboard UI, manual attendance marking, Google Calendar/Meet link generation, Classroom course provisioning
3. **Phase 3** — Payments and payroll
4. **Phase 4** — Remaining notifications (SMS via Twilio, invoice/payroll/submission triggers)
5. **Phase 5** — XLSX exports, desktop launcher scripts, polish
