# Person-Centric Migration Plan (Future-Proof for `student_profile` / `teacher_profile`)

## Summary
Perform a single-cutover migration from separate `Student`/`Teacher` models to a unified `Person` model, while keeping existing `/students` and `/teachers` APIs unchanged. Use Flyway for controlled schema/data migration. Design v1 schema so role-specific fields can be moved later into `student_profile` and `teacher_profile` with minimal API/service churn.

## Key Implementation Changes
1. Domain model refactor
- Introduce `Person` entity with:
  - Shared fields: `id`, `personType`, `name`, `meetEmail`, `googleUserId`, `meetDisplayName`, `active`
  - Role fields (nullable v1): `classroomEmail`, `parentEmail`, `parentPhone`, `phone`, `hourlyRate`
- Reuse existing `PersonType` (`STUDENT`, `TEACHER`) as the discriminator.
- Replace `StudentRepository`/`TeacherRepository` with `PersonRepository` methods scoped by `personType` and shared identity lookups.

2. Relational model simplification
- `attendance`: replace `student_id` + `teacher_id` with `person_id` FK.
- `notification_log`: replace `student_id` + `teacher_id` with `person_id` FK.
- Update uniqueness/dedup keys to include `(person_id, calendarEventId, date, ...)` equivalents.
- Keep soft/progressive DB enforcement:
  - Add baseline checks that `person_type` is non-null and role fields are allowed nullable.
  - Add stricter role-field checks in a follow-up Flyway migration after data cleanup/verification.

3. Service/controller compatibility layer
- Keep `/students` and `/teachers` controller contracts unchanged.
- Internally map:
  - `/students` to `PersonType.STUDENT`
  - `/teachers` to `PersonType.TEACHER`
- Preserve existing DTO shapes (`StudentRequest/Response`, `TeacherRequest/Response`) in v1.
- Refactor attendance + notification logic to single-person flows with `personType`-aware notification routing (parent-email behavior still only for students).

4. Future profile-ready abstraction
- Add an internal profile-access seam now (service-level methods), so role-specific reads/writes are not scattered.
- Avoid direct role-field access in business flows outside that seam.
- This enables later split to `student_profile` / `teacher_profile` without API changes and with mostly repository/service-internal migration.

## Flyway Migration Plan (Single Cutover)
1. Bootstrap migration tooling
- Add Flyway dependency/configuration.
- Move from `ddl-auto=update` to Flyway-managed schema for non-test profiles.

2. Schema + backfill migration
- Create `person` table.
- Backfill from `student` and `teacher` into `person` with `person_type`.
- Add mapping strategy for legacy IDs:
  - Preserve IDs where possible; if collisions are possible, use deterministic remap table in migration and apply remap to dependent tables.
- Add `person_id` to `attendance` and `notification_log`, backfill from legacy FKs.
- Recreate required indexes/unique constraints for new keys.

3. Cutover migration
- Switch app code to read/write only `person_id`.
- Remove legacy FK columns (`student_id`, `teacher_id`) and old tables after successful backfill validation in same release window.
- Add Flyway post-migration validation checks (row counts, orphan checks, duplicate identity checks by type).

4. Rollback strategy
- Pre-cutover DB backup snapshot required.
- If validation fails, rollback by restoring snapshot (single-cutover accepted risk profile).

## Test Plan
1. Repository tests
- Person lookups by type + identity fields (`meetEmail`, `googleUserId`, `meetDisplayName`, `name`) and active filtering.
- Attendance/notification dedup queries using `person_id`.

2. Service tests
- Student/teacher CRUD services (via compatibility layer) still return same DTO contracts.
- Meet participant resolution works for both person types with auto-learn identity updates.
- Notification routing parity:
  - Principal channels unchanged
  - Parent email only for `STUDENT`.

3. API regression tests
- Existing `/students`, `/teachers`, `/attendance`, notification flows remain behaviorally equivalent.
- Attendance record filtering by `PersonType` still works.

4. Migration verification tests
- Seed mixed student/teacher data, run Flyway migrations, assert:
  - No row loss
  - Correct person type assignment
  - Correct FK reassignment in attendance/log tables
  - Uniqueness/dedup semantics preserved.

## Assumptions and Defaults
- Chosen: single cutover, keep existing APIs, soft + progressive constraints, adopt Flyway now.
- Database is primarily H2 today; migration SQL will be written to remain deterministic and portable where practical.
- No public API contract break in this migration; any `/people` API introduction is deferred.
- `student_profile` / `teacher_profile` split is a planned later step, enabled by the new internal profile seam and Flyway baseline.
