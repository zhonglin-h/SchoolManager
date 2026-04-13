**Plan — what I'll deliver**
- Gathered concrete file locations + key snippets.
- Recommend exact edits (frontend) and where to call the existing API.
- Provide verification steps and blockers.

**Findings**

- Backend: attendance model, enum, repo, controller and tests
  - Attendance entity: [backend/src/main/java/com/school/entity/Attendance.java](backend/src/main/java/com/school/entity/Attendance.java#L1-L240)
    - Key fields: `student`, `teacher`, `calendarEventId`, `date`, `status` (enum), `updatedAt`.
  - Attendance status enum: [backend/src/main/java/com/school/entity/AttendanceStatus.java](backend/src/main/java/com/school/entity/AttendanceStatus.java#L1-L20)
    - Values: `PRESENT`, `ABSENT`, `LATE`.
  - Repository: [backend/src/main/java/com/school/repository/AttendanceRepository.java](backend/src/main/java/com/school/repository/AttendanceRepository.java#L1-L120)
    - Useful queries: `findByStudentIdAndCalendarEventIdAndDate`, `findByTeacherIdAndCalendarEventIdAndDate`, `findByStudentIdOrderByDateDescIdDesc`, `findAllOrderByDateDescIdDesc`.
  - Controller (endpoints + upsert logic): [backend/src/main/java/com/school/controller/AttendanceController.java](backend/src/main/java/com/school/controller/AttendanceController.java#L1-L800)
    - GET `/attendance/records`  returns `AttendanceRecordResponse` (includes `id`, `personId`, `personType`, `calendarEventId`, `date`, `status`, `updatedAt`).
    - POST `/attendance/upsert`  `UpsertAttendanceRequest(personId, personType, calendarEventId, eventTitle, date, status)`; controller finds existing by person and event/date and sets `att.setStatus(req.status())` then `attendanceRepository.save(att)`. (No extra validation beyond lookups; personType must be `"STUDENT"` or `"TEACHER"`.)
  - Backend tests touching attendance: [backend/src/test/java/com/school/service/MeetAttendanceMonitorTest.java](backend/src/test/java/com/school/service/MeetAttendanceMonitorTest.java#L1-L340)
    - Shows code expects `AttendanceStatus` enum values and repository save/update behavior. Several tests assert saved `Attendance` have `PRESENT`, `LATE`, or `ABSENT`.

- Frontend: data types, API client, pages, hooks
  - API client and types: [frontend/src/services/api.ts](frontend/src/services/api.ts#L1-L320)
    - `AttendanceRecord` type: `id`, `personId`, `personType`, `personName`, `calendarEventId`, `eventTitle`, `date`, `status`, `updatedAt`.
    - `upsertAttendance(personId, personType, calendarEventId, status, date?, eventTitle?)` exists and posts to `/attendance/upsert`.
    - `getAttendanceRecords(personType?, personId?)` and `getAttendanceToday(live)` exist.
  - Attendance Records page: [frontend/src/pages/AttendanceRecords.tsx](frontend/src/pages/AttendanceRecords.tsx#L1-L200)
    - Uses TanStack Query (`useQuery`) with key `['attendance','records', personTypeFilter]` to fetch records.
    - Columns include `status` column which currently renders static text using `STATUS_STYLES`.
  - Dashboard & today UI: [frontend/src/pages/Dashboard.tsx](frontend/src/pages/Dashboard.tsx#L1-L320)
    - Uses `useAttendanceToday()` (hook) to show today's attendance and Guests; shows status badges and has `QuickAddModal` for registering a guest and calling `upsertAttendance`.
  - Hook for live attendance: [frontend/src/hooks/useAttendanceToday.ts](frontend/src/hooks/useAttendanceToday.ts#L1-L140)
    - Merges DB and live data; updates use React Query and `queryClient.setQueryData`.
  - Existing UI that already calls upsert:
    - [frontend/src/components/QuickAddModal.tsx](frontend/src/components/QuickAddModal.tsx#L1-L160)  when creating a person for a live guest, it calls `upsertAttendance(..., 'PRESENT')`.

- How status is represented & validation
  - Backend enum: `AttendanceStatus { PRESENT, ABSENT, LATE }`  the controller expects that enum (string names).
  - Frontend: strings `'PRESENT' | 'LATE' | 'ABSENT'` (api.ts types).
  - Upsert controller accepts `date` (LocalDate)  sending ISO date string `YYYY-MM-DD` is appropriate. If `date` omitted, controller uses `LocalDate.now()`.

- Data fetching / mutation patterns (frontend)
  - TanStack Query `useQuery` for reads (keys: `['attendance','records', ...]`, `['attendance','today']`).
  - No dedicated mutation hook for attendance yet  `upsertAttendance` in `api.ts` is used directly in components (e.g., `QuickAddModal`).
  - Pattern for invalidation: other components use `queryClient.invalidateQueries({ queryKey: ['attendance','today'] })` after upsert.

**Suggested Implementation (step-by-step)**

1. Frontend  add inline edit control to Attendance Records page
   - File to edit: [frontend/src/pages/AttendanceRecords.tsx](frontend/src/pages/AttendanceRecords.tsx#L1-L200)
   - Replace the `status` column cell renderer with a select/dropdown when the row is editable:
     - If `row.original.personId == null || row.original.personType == null`  render non-editable text (unregistered).
     - Otherwise render a `<select>` (options: `PRESENT`, `LATE`, `ABSENT`) with current value `row.original.status ?? ''`.
     - On change, call a mutation that runs:
       - `upsertAttendance(personId, personType, calendarEventId, newStatus, date, eventTitle)`
       - Use `date` exactly as `row.original.date` (likely `YYYY-MM-DD`)  backend expects LocalDate-compatible string.
   - Provide optimistic UI or loading state per-row (disable while in flight).
   - In success handler invalidate queries:
     - `queryClient.invalidateQueries({ queryKey: ['attendance','records'] })` (with current personTypeFilter to be precise).
     - `queryClient.invalidateQueries({ queryKey: ['attendance','today'] })` and possibly `invalidateQueries(['attendance','today','live'])` to refresh dashboard/live data.

2. Frontend  implement mutation hook (recommended)
   - Add optional helper: `frontend/src/hooks/useUpsertAttendance.ts` or add inside `AttendanceRecords.tsx`:
     - Use `useMutation` from `@tanstack/react-query` calling `upsertAttendance`.
     - OnSuccess: invalidate queries (as above); show toast on error via existing interceptor.
   - This keeps logic consistent with `QuickAddModal` which currently calls `upsertAttendance` directly.

4. No backend API changes required
   - The POST `/attendance/upsert` already implements upsert (create or update) and accepts `status` as `AttendanceStatus` (string values). Use the existing endpoint.

5. UX choices (preferred)
   - Inline select in the Status cell (records table) is the least-disruptive and fastest to implement.
   - Alternative: row action (Edit) opens a small modal  useful if you need audit/correction reasons or comment capture.
   - Recommendation: start with inline select; add audit/comment modal later if needed.

**Exact files & symbols to edit**
- Frontend
  - [frontend/src/pages/AttendanceRecords.tsx](frontend/src/pages/AttendanceRecords.tsx#L1-L200)
    - Symbol: column definition array  change the `status` column `cell` renderer to a controlled `<select>` and wire mutation.
  - (Optional) [frontend/src/hooks/useAttendanceToday.ts](frontend/src/hooks/useAttendanceToday.ts#L1-L140)
    - If adding dashboard edits, ensure live query invalidation or refetch behavior remains intact.
  - (Optional) [frontend/src/pages/Dashboard.tsx](frontend/src/pages/Dashboard.tsx#L1-L320)
    - Symbol: `AttendanceCard` component; change `statusBadge` + row actions to editable control.

- Backend (read-only; no edits needed)
  - [backend/src/main/java/com/school/controller/AttendanceController.java](backend/src/main/java/com/school/controller/AttendanceController.java#L1-L800)
    - Symbol: `@PostMapping("/upsert") public ResponseEntity<Void> upsert(...)` and `UpsertAttendanceRequest` record.
  - [backend/src/main/java/com/school/entity/AttendanceStatus.java](backend/src/main/java/com/school/entity/AttendanceStatus.java#L1-L20)
    - Enum values: `PRESENT, ABSENT, LATE`.

**Example frontend snippet (what to call on change)**
- Call (JS/TS):
  - `await upsertAttendance(personId, personType, calendarEventId, newStatus, date, eventTitle)`
- After success:
  - `queryClient.invalidateQueries({ queryKey: ['attendance','records'] })`
  - `queryClient.invalidateQueries({ queryKey: ['attendance','today'] })`

**Verification steps**
1. Implement frontend changes (AttendanceRecords.tsx).
2. Start backend and frontend:
   - Backend (from repo root):
     - On Windows PowerShell:
       ```bash
       cd backend
       ./gradlew bootRun
       ```
   - Frontend:
     ```bash
     cd frontend
     pnpm install
     pnpm dev
     ```
3. Open the app: visit the Attendance Records page (route `/attendance`).
4. Confirm:
   - Editable rows show a select for `Status` (for registered rows only).
   - Changing value triggers the mutation and the table updates (new `status` and `updatedAt`).
   - Dashboard (`/`) reflects the change after invalidation/refetch.
5. Run backend tests (optional):
   ```bash
   cd backend
   ./gradlew test
   ```
   - Ensure existing attendance-related tests still pass.

**Potential blockers / notes**
- Unregistered rows: entries with `personId == null` or `personType == null` cannot be edited  disable editing for those and surface that limitation to users.
- Missing `calendarEventId` or malformed `date`: the upsert endpoint requires `personId` and `personType` to find the existing record; providing `date` and `calendarEventId` is recommended to target the exact row. If `calendarEventId` is null, upsert will create an attendance record with null `calendarEventId` which may be undesirable.
- Enum mapping: backend expects exact strings `PRESENT`, `LATE`, `ABSENT`. Ensure frontend sends those exact values.
- Concurrency: multiple edits could create/update race conditions  server uses find-or-create semantics and unique constraints (`student_id`, `calendarEventId`, `date`)  this should prevent duplicate entries but may surface constraint exceptions if payloads are inconsistent.
- Audit trail: there is no built-in comment/reason capture on status change. If you need audit metadata for corrections, backend changes and a new `AttendanceCorrection` record or adding fields to `Attendance` may be required.
- Tests: there are backend unit tests for automated attendance recording (Meet monitor); adding UI does not require server test changes. If you add server-side auditing, update tests accordingly.

If you want, I can:
- Draft the exact code patch for `AttendanceRecords.tsx` (select cell + mutation), including `useMutation` boilerplate and query invalidation.
- Optionally add a small `useUpsertAttendance` hook and update `Dashboard` as well. Which do you prefer next?
