# School Manager

## Getting Started (Windows)

### Prerequisites

| Tool | Minimum version | Install command |
|------|----------------|-----------------|
| Java JDK | 21 | `winget install Microsoft.OpenJDK.21` |
| Node.js | 20 LTS | `winget install OpenJS.NodeJS.LTS` |
| pnpm | latest | `npm install -g pnpm` (setup.bat does this automatically) |

### One-Time Setup

```bat
:: Clone the repository
git clone https://github.com/zhonglin-h/SchoolManager.git
cd SchoolManager

:: Run the setup script — checks prerequisites, installs deps, creates config file
setup.bat
```

`setup.bat` will:
1. Verify Java 21 and Node.js are installed.
2. Install pnpm if missing.
3. Pre-fetch all frontend and backend dependencies.
4. Download Playwright browser binaries (needed for Meet auto-join).
5. Create `backend/src/main/resources/application-local.properties` from the template and open it in Notepad.

After setup, follow **`credentials-checklist.md`** to fill in your Google OAuth credentials, Gmail App Password, and other required values.

### Daily Use

```bat
start.bat    :: Build if needed, launch the app, open http://localhost:8080
stop.bat     :: Stop the running app
```

### Docker (Mac / Linux / Windows)

```bash
# One-time: place credentials, then:
docker compose up --build    # first run
docker compose up -d         # subsequent runs
docker compose down
```

See `credentials-checklist.md` for credential placement instructions.

---

## Development Setup

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

### Meet Auto-Join Setup (Playwright)

Auto-join uses Playwright + a dedicated Chrome profile on the local machine.  
Use a separate automation profile, not your regular daily-use Chrome profile.

#### 1. Required properties

Add these to `backend/src/main/resources/application-local.properties`:

```properties
# Enable auto-join scheduler
app.autojoin.enabled=true

# Use Playwright provider
app.autojoin.provider=playwright

# Browser automation settings
app.autojoin.chrome-profile-dir=C:/chrome-autojoin/UserData/Profile 1
app.autojoin.chrome-path=C:/Program Files/Google/Chrome/Application/chrome.exe
app.autojoin.require-principal-profile-signed-in=true

# Join behavior
app.autojoin.join-timeout-seconds=45
app.autojoin.retry.max-attempts=1
app.autojoin.retry.backoff-ms=1000
```

Notes:
- Use forward slashes in Windows paths in `.properties`.
- `bootRun` uses the `local` profile in this project, so put overrides in `application-local.properties`.

#### 2. Create a dedicated Chrome automation profile

1. Create a new local folder, for example `C:/chrome-autojoin/UserData`.
2. Launch Chrome once using that user data dir to create the profile:
   - `"C:/Program Files/Google/Chrome/Application/chrome.exe" --user-data-dir="C:/chrome-autojoin/UserData"`
3. In that Chrome window:
   - Sign in as the principal Google account.
   - Open a Meet once and grant mic/camera permissions.
   - Disable extensions that might interfere with automation.
4. Close Chrome.

Why this matters:
- Avoids policy/extension conflicts from your normal profile.
- Keeps automation state isolated and predictable.

#### 3. Verify manual join endpoint

Sync events, find today's space codes, then trigger manual join:

```bash
curl -X POST "http://localhost:8080/calendar/sync"
curl "http://localhost:8080/attendance/today/space-codes"
curl -X POST "http://localhost:8080/calendar/<eventId>/join"
```

Expected:
- Join attempt recorded with status `JOINED` (or `ALREADY_OPEN_ELSEWHERE` when Meet is already open elsewhere).

#### 4. Verify scheduled AUTO_JOIN at T-15

When `app.autojoin.enabled=true`, auto-join is gated at T-15:
- If meeting is not active at T-15 -> app attempts auto-join.
- If already active -> app skips auto-join.

Helpful log lines:
- `Scheduling AUTO_JOIN gate at T-15 ...`
- `AUTO_JOIN gate fired ...`
- `AUTO_JOIN proceeding ...` or `AUTO_JOIN skipped ...`

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
