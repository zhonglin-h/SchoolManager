# School Manager

## Setup

### Local Files To Create

When cloning on a new machine, create these gitignored files:

### `client_secret.json` (project root)
Download from Google Cloud Console -> APIs & Services -> Credentials -> OAuth 2.0 Client ID (Desktop app).

### `backend/src/main/resources/application-local.properties`
```properties
google.calendar.id=<calendar email>
app.principal.name=<principal name>
app.principal.email=<principal email>
app.principal.google-user-id=<google user id>

app.meet.mock=false

spring.mail.username=<gmail address>
spring.mail.password=<gmail app password>

app.notifications.enabled=true
```

> For `spring.mail.password`, use a Gmail App Password (not your account password).
> Generate one at myaccount.google.com -> Security -> 2-Step Verification -> App passwords.

### `frontend/.env.local`
```
VITE_API_BASE_URL=http://localhost:8080
```

### `.vscode/settings.json`
VS Code recreates this automatically. Only the committed `tasks.json` and `launch.json` are needed.

### Google Cloud And Google Account Setup

This project uses Google Cloud OAuth (user-based, not service account) for:
- Google Calendar API reads
- Google Meet API reads

### 1. Create or select a Google Cloud project
- Open Google Cloud Console and choose the project this app should use.

### 2. Enable required APIs
- Enable `Google Calendar API`.
- Enable `Google Meet API`.

### 3. Configure OAuth consent screen
- Configure the OAuth consent screen for your organization/user type.
- Add required scopes:
  - `https://www.googleapis.com/auth/calendar.readonly`
  - `https://www.googleapis.com/auth/meetings.space.readonly`
- If app publishing status is Testing, add the principal account as a test user.

### 4. Create OAuth client credentials
- Create an OAuth Client ID of type `Desktop app`.
- Download the credentials JSON file.
- Save it as `client_secret.json` at the repository root.

### 5. Configure local app properties
- Set `google.calendar.id` in `application-local.properties` to the monitored calendar.
- Set principal fields:
  - `app.principal.name`
  - `app.principal.email`
  - `app.principal.google-user-id`
- Set `app.meet.mock=false` to use real Meet API calls.

### 6. Complete first OAuth login
- Start backend once (`./gradlew bootRun`).
- On first run, the app opens a browser for Google consent.
- Approve the requested scopes.
- Tokens are cached locally (default path `./data/tokens`).

### 7. Verify API access works
- Confirm the app can load today's calendar events.
- Confirm meeting-active/participant checks work with `app.meet.mock=false`.

### Auto-Join Prerequisites (Windows, Principal Account)

If you enable the planned browser auto-join flow:
- Keep existing backend OAuth as-is for API access.
- Keep a dedicated Chrome profile signed in as the principal account.
- Use that principal profile for automation; this is separate from backend OAuth tokens.
- Pre-grant Meet permissions on that profile (mic/camera prompts handled once).

No separate new Google OAuth system is required for auto-join.

---

## Running the App

### Backend

```bash
cd backend

# Start the server (port 8080)
./gradlew bootRun

# Watch for changes and restart automatically
./gradlew bootRun --continuous

# Compile only, watch for changes
./gradlew compileJava --continuous
```

### Frontend

```bash
cd frontend

# Install dependencies (first time only)
pnpm install

# Start the dev server (port 3000)
pnpm dev
```

### Full Stack

```bash
# Terminal 1
cd backend && ./gradlew bootRun

# Terminal 2
cd frontend && pnpm dev
```

Visit http://localhost:3000

---

Note on tests
-
By default `./gradlew test` skips long-running or integration-style tests that are tagged with `@Tag("manual")`.
To run those explicitly (for example the Telegram config integration test), run:

```bash
cd backend
./gradlew manualTest
```

This keeps the regular test suite fast while still allowing manual/integration tests to be executed when needed.
