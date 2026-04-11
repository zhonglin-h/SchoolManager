# Plan: Show Actual Recipient Emails in Notification Log

## Context
The Notifications page currently shows "Student #5" or "Principal" in the RECIPIENT column. This is unhelpful — the user wants to see the actual email addresses that were notified. The `NotificationLog` entity stores only a `Student` reference but never records which email addresses were actually sent to.

## Changes Required

### 1. `NotificationLog` entity — add `recipient` field
**File:** `backend/src/main/java/com/school/entity/NotificationLog.java`

Add a `String recipient` field to store the comma-separated list of contact addresses that were notified (email addresses for the `EMAIL` channel, phone numbers for the `SMS` channel).

```java
@Column(length = 500)
private String recipient; // e.g. "principal@school.com, parent@email.com" or "+61412345678"
```

### 2. `NotificationService.notify()` — populate `recipient`
**File:** `backend/src/main/java/com/school/service/NotificationService.java`

Before building the log entry, collect the contact addresses that were attempted for the channel in use:
- For `EMAIL` channel: if `type.toPrincipal()` → add `principalEmail`; if `type.toParent()` and student has a non-blank `parentEmail` → add `student.getParentEmail()`
- For `SMS` channel (future): collect phone number(s) analogously

Join with `", "` and store in the log builder.

### 3. `NotificationLogResponse` DTO — expose `recipient`
**File:** `backend/src/main/java/com/school/dto/NotificationLogResponse.java`

Add `String recipient` to the record, mapped from `log.getRecipient()`.

### 4. Frontend Notifications page — display `recipient`
**File:** `frontend/src/pages/Notifications.tsx`

Update the RECIPIENT column to render `n.recipient` (the email string) instead of the current `n.studentId !== null ? 'Student #' + n.studentId : 'Principal'` logic.

Also update the TypeScript type/interface for `NotificationLog` (wherever it's defined, likely in a `types.ts` or inline) to include `recipient: string`.

## Data Migration Note
H2 with Hibernate `ddl-auto=update` will automatically add the new column. Existing rows will have `recipient = NULL`, which the frontend should handle gracefully (fall back to empty string or "—").

## Verification
1. Start backend (`./gradlew bootRun`) and frontend (`pnpm dev`)
2. Trigger a notification (e.g. wait for or manually invoke the Meet attendance monitor)
3. Open `http://localhost:3000/notifications`
4. Confirm the RECIPIENT column shows the actual email address(es) instead of "Student #N"
5. Check that rows with `NULL` recipient (old records) display "—" without crashing
