**Attendance status edit — condensed plan**

Goal: let the principal correct attendance status (PRESENT / LATE / ABSENT) from the frontend by calling the existing POST `/attendance/upsert` endpoint.

Key facts
- Backend: `POST /attendance/upsert` accepts `personId, personType, calendarEventId, date, status` and performs create-or-update. Enum values: `PRESENT`, `LATE`, `ABSENT`.
- Frontend: `upsertAttendance(...)` exists in `frontend/src/services/api.ts`; records are fetched via TanStack Query keys `['attendance','records', ...]` and `['attendance','today']`.

Minimal implementation
- Edit [frontend/src/pages/AttendanceRecords.tsx](frontend/src/pages/AttendanceRecords.tsx#L1-L200): change the `status` column to an editable control (preferred: small modal opened by an Edit icon; or an inline `<select>`).
- On save, call `upsertAttendance(personId, personType, calendarEventId, newStatus, date, eventTitle)` via a `useMutation`, then invalidate `['attendance','records']` and `['attendance','today']`.
- Disable editing for rows with missing `personId` or `personType`.

UX recommendation
- Use a compact modal for edits to avoid accidental changes; inline select is acceptable for power users.

Verification
- Start backend and frontend, open Attendance Records, edit a status, confirm table and dashboard update, and optionally run `./gradlew test`.

Notes / constraints
- Send exact enum strings: `PRESENT`, `LATE`, `ABSENT`.
