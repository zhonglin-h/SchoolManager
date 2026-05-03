# School Manager

A local, single-user web application for managing students, classes, attendance, payments, and payroll. Runs entirely on `localhost`; no cloud deployment needed.

---

## Prerequisites

| Tool | Minimum version | Install |
|------|----------------|---------|
| Java JDK | 21 | `winget install Microsoft.OpenJDK.21` |
| Node.js | 20 LTS | `winget install OpenJS.NodeJS.LTS` |
| pnpm | latest | `npm install -g pnpm` |

---

## First-Time Setup

### 1. Clone and run setup

```powershell
git clone https://github.com/zhonglin-h/SchoolManager.git
cd SchoolManager
.\setup.ps1
```

`setup.ps1` will verify prerequisites, install frontend and backend dependencies, download Playwright browser binaries, and create `backend/src/main/resources/application-local.properties` from the template.

### 2. Configure credentials

Open `backend/src/main/resources/application-local.properties` (`setup.ps1` opens it automatically) and fill in:

```properties
google.calendar.id=<calendar email>
app.principal.name=<principal name>
app.principal.email=<principal email>
app.principal.google-user-id=<google user id>

spring.mail.username=<gmail address>
spring.mail.password=<gmail app password>   # App Password, not account password

app.meet.mock=false
app.notifications.enabled=true
```

Generate a Gmail App Password at: myaccount.google.com -> Security -> 2-Step Verification -> App passwords.

Also create `frontend/.env.local`:

```
VITE_API_BASE_URL=http://localhost:8080
```

### 3. Set up Google Cloud

This app uses Google OAuth (user-based) for Calendar and Meet API access.

1. **Create or select a Google Cloud project** in the Google Cloud Console.
2. **Enable APIs**: Google Calendar API and Google Meet API.
3. **Configure the OAuth consent screen** - add these scopes:
   - `https://www.googleapis.com/auth/calendar.readonly`
   - `https://www.googleapis.com/auth/meetings.space.readonly`
   - If app status is *Testing*, add the principal account as a test user.
4. **Create an OAuth 2.0 Client ID** of type *Desktop app*, download the JSON, and save it as `backend/client_secret.json`.
5. **First run** - run `./start.ps1` from the repository root. If Google consent is required, the script opens the OAuth URL in your browser. Approve the scopes. Tokens are cached at `./data/tokens` and reused on subsequent starts.

### 4. Set up Meet auto-join (optional)

Auto-join uses Playwright with a dedicated Chrome profile signed in as the principal account.

**Create the Chrome profile:**

```powershell
"C:/Program Files/Google/Chrome/Application/chrome.exe" --user-data-dir="C:/chrome-autojoin/UserData"
```

In that Chrome window: sign in as the principal, open a Meet once to grant mic/camera permissions, then close Chrome.

**Add to `application-local.properties`:**

```properties
app.autojoin.enabled=true
app.autojoin.provider=playwright
app.autojoin.chrome-profile-dir=C:/chrome-autojoin/UserData/Profile 1
app.autojoin.chrome-path=C:/Program Files/Google/Chrome/Application/chrome.exe
app.autojoin.require-principal-profile-signed-in=true
app.autojoin.join-timeout-seconds=45
app.autojoin.retry.max-attempts=1
app.autojoin.retry.backoff-ms=1000
```

Use forward slashes in Windows paths inside `.properties` files. Auto-join fires at T-15 min if the meeting is not yet active; it is skipped if already active.

---

## Daily Use

```powershell
.\start.ps1   # Build if needed, start the app, open http://localhost:8080
.\stop.ps1    # Stop the running app
```

### Docker (Mac / Linux / Windows)

```bash
docker compose up --build   # first run
docker compose up -d        # subsequent runs
docker compose down
```

---

## Development

Run the backend and frontend separately for hot-reload during development:

```bash
# Terminal 1 - backend on :8080
cd backend
./gradlew bootRun

# Terminal 2 - frontend on :3000
cd frontend
pnpm install   # first time only
pnpm dev
```

Visit `http://localhost:3000`. The Vite dev server proxies `/api` requests to the backend.

Other useful backend commands:

```bash
./gradlew bootRun --continuous    # restart on file changes
./gradlew compileJava --continuous
./gradlew build
```

---

## Testing

```bash
cd backend
./gradlew test           # runs all tests (excludes @Tag("manual") tests)
./gradlew manualTest     # runs only @Tag("manual") integration/long-running tests
```
