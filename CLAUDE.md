# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

School Manager is a local, single-user full-stack web application for a school principal to manage students, teachers, classes, attendance, payments, and payroll. No authentication is required — it runs entirely on `localhost`. The design is documented in `High Level Design.md`.

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Spring Boot (Java), Gradle |
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
./gradlew bootRun                          # Start on :8080
./gradlew build                            # Build
./gradlew test                             # Run all tests
./gradlew test --tests "*.ClassName"       # Run a single test class
```

### Frontend (once scaffolded)
```bash
cd frontend
pnpm install
pnpm dev           # Start Vite dev server on :3000
pnpm build         # Production build
pnpm lint          # ESLint
```

### Full stack
```bash
# Terminal 1
cd backend && ./gradlew bootRun

# Terminal 2
cd frontend && pnpm dev
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

### Data Model (13 entities)
- **Student** — `meetEmail` for Meet matching, `classroomEmail` for Classroom matching; soft-deleted via `active` flag
- **Term** — academic periods
- **Teacher** — includes `hourlyRate`
- **Class** — links to Teacher and Term; holds `calendarEventId`, `googleMeetLink`, `googleClassroomId`, and local schedule fields
- **ClassCancellation** — individual cancelled sessions synced from Google Calendar
- **Enrollment** — many-to-many Student ↔ Class
- **Attendance** — per student per class session (`PRESENT` / `ABSENT` / `LATE`)
- **Assignment** — synced from Google Classroom; has `teacherPostedOnTime` flag
- **StudentSubmission** — synced from Classroom; has `submittedOnTime` flag
- **Invoice** — student billing (`UNPAID` / `PAID` / `OVERDUE`)
- **Payment** — records individual payments against invoices
- **SessionLog** — teacher work sessions for payroll (duration in minutes)
- **NotificationLog** — immutable audit trail of every notification sent; deduplication key is `(studentId, classId, date, type)`

### API Design
- Backend exposes a REST API at `http://localhost:8080`
- Frontend uses `VITE_API_BASE_URL=http://localhost:8080`
- All responses use standard HTTP status codes; errors return a JSON body with `message`

### Google API Integration
- Uses a **Service Account** (credentials at `./service-account.json`, never committed)
- **Google Calendar**: source of truth for class schedules; app reads RRULE + attendees + Meet link from events; nightly exception sync populates `ClassCancellation`
- **Google Meet**: live attendance monitor via Spring `TaskScheduler` — schedules per-class polling tasks at T−15min, T−5min, class start, and class end; queries `spaces/{spaceId}/participants`
- **Google Classroom**: roster sync and assignment/submission pull; matched by `classroomEmail`

### Notification Triggers
Automated notifications are logged to `NotificationLog`. See `High Level Design.md` for the full trigger table.

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

## Tooling

### Serena MCP
Serena is available as an MCP server and provides semantic coding tools for codebase exploration and editing. Always prefer Serena's tools over raw file reads when doing code work:

- Use `mcp__serena__get_symbols_overview` and `mcp__serena__find_symbol` to explore code structure instead of reading entire files.
- Use `mcp__serena__search_for_pattern` for flexible codebase searches.
- Use `mcp__serena__replace_symbol_body`, `mcp__serena__insert_after_symbol`, etc. for targeted edits.
- Serena tools are **deferred** — load their schemas first with `ToolSearch` (e.g., `select:mcp__serena__find_symbol`) before calling them.
- Open the Serena dashboard at http://127.0.0.1:24283/dashboard/index.html or via `mcp__serena__open_dashboard`.

## Plans

Feature plans are stored in `./Plans/`. When asked to plan a feature or enhancement, save the plan as a Markdown file there.

## Development Phases

See `Build Phases.md` for the full incremental delivery plan. Summary:

1. **Phase 1** — Student registry + Calendar-driven Meet attendance monitor + email notifications
2. **Phase 2** — Local class management + full Calendar integration + dashboard
3. **Phase 3** — Google Classroom roster and assignment sync
4. **Phase 4** — Payments and payroll
5. **Phase 5** — Remaining notifications (SMS via Twilio + additional triggers)
6. **Phase 6** — XLSX exports, desktop launcher scripts, polish
