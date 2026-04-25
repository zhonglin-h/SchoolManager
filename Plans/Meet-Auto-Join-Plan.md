# Google Meet Auto-Join Plan (Aligned With Current Codebase)

## Objective
Add best-effort Windows auto-join on top of the existing Calendar + Meet monitoring system, with reliable fallback alerts when unattended join fails.

## Current State (Already Implemented)
- Google OAuth desktop flow with local token cache (`client_secret.json`, `google.tokens.dir`).
- Google Calendar API read for today's events and Meet links.
- Google Meet API read for meeting-active checks and participant snapshots.
- Scheduler and monitor pipeline for T-15, T-3, session start polling, and finalize.
- Backend OAuth is already the source of truth for Google API access; no new OAuth system is needed for auto-join.

This plan focuses only on missing auto-join capabilities.

## 1. Scope And Guardrails
- Support internal Google Workspace meetings first.
- Use the principal's Google account as the dedicated auto-join account.
- Treat unattended join as best-effort automation, not guaranteed.
- Define terminal outcomes:
  - `joined`
  - `failed_auth`
  - `failed_permission`
  - `failed_waiting_room_timeout`
  - `failed_ui_not_found`
  - `failed_network`
  - `failed_unknown`
- Fallback requirement: escalate to principal/operator after timeout.

## 2. Architecture Decision (Local-Only First)
- Run join automation on the same Windows machine that already runs backend.
- Keep backend as orchestrator and state owner.
- Keep existing backend OAuth flow unchanged for Calendar/Meet APIs.
- Browser auto-join uses principal Chrome session state (signed-in profile/cookies), not API OAuth tokens.
- Add a local `JoinAutomationClient` abstraction with two implementations:
  - `NoopJoinAutomationClient` for environments where automation is disabled.
  - `PlaywrightJoinAutomationClient` (or Selenium equivalent) for real joins.
- Do not introduce distributed worker leasing yet.

## 3. Integration With Existing Scheduler
- Reuse current event scheduling from `MeetAttendanceMonitor`.
- Add a new per-event trigger around class start (for example T-1 minute).
- New handler in `MeetSessionHandler` or a dedicated `MeetJoinHandler`:
  - Validate event has Meet link and space code.
  - Skip if room already active (optional policy toggle).
  - Execute join attempt.
- Keep current attendance polling flow unchanged after join attempt.

## 4. Join Attempt Flow (Windows Browser Automation)
- Launch Chrome with a dedicated signed-in profile for the principal account.
- Navigate to Meet URL.
- Ensure mic and camera are disabled before attempting to join.
- Handle UI branches:
  - `Join now`
  - `Ask to join`
  - Waiting room/admit screen
  - Account/permission prompts
- Retry transient selector failures with bounded backoff.
- Emit final status + reason code back to backend logs/state.

## 5. Data Model And API Additions
- Add `join_attempt_log` table (or equivalent entity) with:
  - `calendarEventId`
  - `scheduledStart`
  - `attemptedAt`
  - `status`
  - `reasonCode`
  - `detailMessage`
- Add backend endpoint(s):
  - `GET /calendar/join-attempts/today`
  - Optional manual trigger: `POST /calendar/{eventId}/join`
- Keep idempotency key as `(calendarEventId, date, triggerType)` to avoid duplicate attempts.

## 6. Configuration Additions
- `app.autojoin.enabled`
- `app.autojoin.provider` (`playwright` or `selenium`)
- `app.autojoin.trigger-offset-seconds`
- `app.autojoin.join-timeout-seconds`
- `app.autojoin.chrome-profile-dir`
- `app.autojoin.chrome-path` (optional override)
- `app.autojoin.retry.max-attempts`
- `app.autojoin.retry.backoff-ms`
- `app.autojoin.notify-on-success` (default `false`)
- `app.autojoin.require-principal-profile-signed-in` (default `true`)

## 7. Notifications And Fallback
- On failed unattended join, notify principal through existing notification channels.
- Add distinct notification type: `AUTO_JOIN_FAILED`.
- If `app.autojoin.notify-on-success=true`, send principal notification on successful join.
- Add distinct notification type: `AUTO_JOIN_SUCCESS`.
- Deduplicate by event/date/reason to prevent alert spam.

## 8. Security/Operations
- Keep the principal auto-join profile isolated from other personal browser profiles.
- Restrict filesystem access for token and browser profile directories.
- Document one-time manual setup:
  - First backend OAuth consent login (existing flow, for API access)
  - Chrome profile sign-in with principal account
  - Browser permission grants for Meet camera/mic prompts
- Document recovery steps when Chrome session expires (re-login principal profile); no backend OAuth redesign required.

## 9. Testing Strategy
- Unit tests:
  - Trigger scheduling decisions
  - Idempotency and dedup behavior
  - Failure classification mapping
- Integration tests:
  - Mock `JoinAutomationClient` success/failure paths
  - Verify monitor continues even if join automation fails
- Manual validation:
  - Real Workspace meeting dry run across at least one school week

## 10. Rollout Phases
1. Phase 1: Auto-open only with explicit operator confirmation.
2. Phase 2: Unattended join in pilot mode for limited classes/accounts.
3. Phase 3: Default-on for selected schedules with alerting and runbook.

## Exit Criteria
- Pilot unattended join success >=95%.
- Median delay from trigger to joined <=30 seconds.
- 100% of failed attempts produce reason-coded logs and principal alert.

## Implementation Checklist (Delta From Current Code)
1. Add auto-join config properties and metadata.
2. Add join attempt entity/repository/service.
3. Add `JoinAutomationClient` interface and `Noop` implementation.
4. Implement Windows browser automation provider.
5. Wire auto-join trigger into current monitor scheduling.
6. Add failure notifications and dedup.
7. Add endpoints/UI hooks for join attempt visibility.
8. Add startup/preflight check that principal Chrome profile is signed in.
9. Execute pilot and tune selectors/retries/timeouts.
