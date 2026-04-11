# School Manager

## Setup

When cloning on a new machine, the following files are gitignored and must be created manually:

### `client_secret.json` (project root)
Download from Google Cloud Console → APIs & Services → Credentials → OAuth 2.0 Client ID.

### `backend/src/main/resources/application-local.properties`
```properties
google.calendar.id=<calendar email>
app.principal.email=<principal email>
app.principal.google-user-id=<google user id>

app.meet.mock=false

spring.mail.username=<gmail address>
spring.mail.password=<gmail app password>

app.notifications.enabled=true
```

> For `spring.mail.password`, use a Gmail App Password (not your account password).
> Generate one at myaccount.google.com → Security → 2-Step Verification → App passwords.

### `frontend/.env.local`
```
VITE_API_BASE_URL=http://localhost:8080
```

### `.vscode/settings.json`
VS Code recreates this automatically. Only the committed `tasks.json` and `launch.json` are needed.

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
