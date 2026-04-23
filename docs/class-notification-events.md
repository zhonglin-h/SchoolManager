# Notification Trigger Summary

## Class Session Notifications

Scheduled per class session by the Meet attendance monitor.

| Time | Condition | Recipient | Channel |
|------|-----------|-----------|---------|
| T − 15 min | Reminder for principal to start class | Principal | Telegram |
| T − 3 min | Student or teacher not yet joined | Principal + Parent (per absent student) | Telegram (Principal), Email (Parent) |
| T + 0 onward | Each new arrival joins | Principal | Telegram |
| T + 0 onward | All students and teacher present | Principal | Telegram |
| After T + 0 | Student joins late | Principal | Telegram |
| Session end | Student never joined | Principal | Telegram |

## Non-Session Notifications

| Trigger | Recipient | Channel |
|---------|-----------|---------|
| Invoice becomes overdue | Principal + Parent | Email + SMS |
| Payment recorded | Principal | Email |
| Teacher did not post homework within 2 days of class | Principal | Email |
| Student missed submission deadline | Principal + Parent | Email |
| Teacher payroll due | Principal | Email |

## Deduplication

Every notification is recorded in `NotificationLog`. The dedup key is `(studentId, classId, date, type)` — a given notification type fires at most once per student per session. Principal-only notifications use `(classId, date, type)` with `studentId` nullable.
