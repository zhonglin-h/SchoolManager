# Portability Plan — School Manager

## Goal

Make School Manager trivially installable on a new Windows machine:
one command to set up, one command to run, with clear guidance on credentials.

---

## Current Pain Points

| Problem | Impact |
|---------|--------|
| Java 21 JDK must be manually installed | Prerequisite not obvious |
| Node.js + pnpm must be manually installed | Prerequisite not obvious |
| Frontend and backend must be started as two separate processes | Fragile, two terminals |
| Playwright browser binaries must be downloaded separately | Often forgotten |
| Credentials (service-account.json, SMTP, Twilio) have no template or checklist | Silent failures at runtime |
| `client_secret.json` sits loose in `backend/` | Easy to forget to copy |
| No setup script or single-page install guide | Every new machine is a puzzle |

---

## Target State

```
# One-time setup (new machine)
setup.bat

# Every day
start.bat          # opens http://localhost:8080 — one process, no Vite needed
stop.bat
```

- **Single process**: Spring Boot serves the built React app as static files.
  No separate Node/Vite server needed at runtime.
- **Single JAR**: `./gradlew bootJar` produces a self-contained fat JAR.
- **Docker Compose alternative**: for anyone who prefers containers.
- **Credential template**: a checked-in template with every required key documented.

---

## Phases

### Phase 1 — Bundle frontend into Spring Boot (core change)

**Why first**: eliminates the Node runtime dependency at run-time and the
two-process startup. Everything else builds on this.

**Steps:**

1. **Gradle build task** — add a task in `build.gradle` that:
   - Runs `pnpm install && pnpm build` inside `frontend/`
   - Copies `frontend/dist/**` → `backend/src/main/resources/static/`
   - Is wired as a dependency of `bootJar` and `bootRun` (optional for dev)

2. **SPA fallback controller** — add a Spring MVC controller that serves
   `index.html` for any GET request not matched by `/api/**`, so React Router
   deep links work:
   ```java
   @Controller
   public class SpaController {
       @GetMapping(value = {"/{path:[^\\.]*}", "/{path:[^\\.]*}/**"})
       public String forward() { return "forward:/index.html"; }
   }
   ```

3. **Vite base URL** — set `base: '/'` in `vite.config.ts` (already default,
   confirm it is not set to a sub-path).

4. **Frontend `.env.local` at build time** — for the production build the API
   base URL is the same origin (`/`), so no proxy or absolute URL needed.
   Update `vite.config.ts` to proxy `/api` to `localhost:8080` only in dev
   mode; in prod the requests go to the same origin automatically.

**Result**: `./gradlew bootJar` → one fat JAR; `java -jar build/libs/*.jar`
opens the full app at `http://localhost:8080`. Node is only needed to build,
not to run.

---

### Phase 2 — Credential template and startup validation

**Steps:**

1. **`application.properties.template`** (checked in) — mirrors
   `application.properties` with every required key, placeholder values, and
   a one-line comment explaining each:
   ```properties
   # Copy this file to application-local.properties and fill in real values.
   spring.mail.username=your-gmail@gmail.com
   spring.mail.password=your-16-char-app-password
   twilio.account-sid=ACxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
   twilio.auth-token=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
   twilio.from-number=+1xxxxxxxxxx
   google.credentials.path=./service-account.json
   ```

2. **`credentials-checklist.md`** (checked in) — step-by-step guide:
   - Where to get the Google Service Account JSON
   - How to generate a Gmail App Password
   - How to find Twilio credentials
   - Where to place `service-account.json` and `client_secret.json`

3. **Startup validation bean** — a `@Component` that runs on startup and logs
   clear `ERROR` messages (not stack traces) for any missing credential file or
   blank required property, then exits gracefully. Prevents cryptic NPEs later.

---

### Phase 3 — Windows launcher scripts

**Steps:**

1. **`setup.bat`** — run once on a new machine:
   - Checks for Java 21 (`java -version`); if missing, prints winget install
     command and exits
   - Checks for Node.js; if missing, prints winget install command and exits
   - Checks for pnpm; installs via `npm install -g pnpm` if missing
   - Runs `cd frontend && pnpm install` to pre-fetch npm deps
   - Runs `.\backend\gradlew dependencies` inside `backend/` to pre-fetch
     Gradle deps (works offline after this)
   - Runs `.\backend\gradlew -p backend playwright-install` (or equivalent)
     to download Playwright browser binaries
   - Copies `application.properties.template` → `backend/application-local.properties`
     if the file does not already exist, then opens it in Notepad for editing
   - Prints a final checklist of credential files the user must place manually

2. **`start.bat`** — daily driver:
   - Calls `.\backend\gradlew bootJar` (skips if JAR is up to date)
   - Starts `java -jar backend\build\libs\*.jar --spring.profiles.active=local`
     as a background process, writing PID to `.pid`
   - Opens `http://localhost:8080` in the default browser after a short wait

3. **`stop.bat`** — kills the process recorded in `.pid`

4. **`build.bat`** — convenience wrapper: `cd frontend && pnpm build`, then
   `cd ..\backend && gradlew bootJar`

---

### Phase 4 — Docker Compose (alternative path)

For users who prefer containers or are on Mac/Linux.

**Steps:**

1. **Multi-stage `Dockerfile`**:
   - Stage 1 (`node`): install pnpm, run `pnpm build` in `frontend/`
   - Stage 2 (`gradle`): copy frontend dist into `src/main/resources/static/`,
     run `./gradlew bootJar`
   - Stage 3 (`eclipse-temurin:21-jre`): copy JAR, expose 8080, `ENTRYPOINT`

2. **`docker-compose.yml`**:
   ```yaml
   services:
     app:
       build: .
       ports:
         - "8080:8080"
       volumes:
         - ./data:/app/data                        # H2 database persists
         - ./service-account.json:/app/service-account.json:ro
         - ./backend/application-local.properties:/app/application-local.properties:ro
       environment:
         - SPRING_PROFILES_ACTIVE=local
   ```

3. **`.dockerignore`** — exclude `node_modules`, `build`, `data`, `backups`,
   credential files.

**Usage**:
```bash
# One-time: fill in credentials, place service-account.json
docker compose up --build    # first time
docker compose up -d         # subsequent
docker compose down
```

---

### Phase 5 — .gitignore audit and README

**Steps:**

1. **`.gitignore` audit** — verify the following are ignored:
   - `data/`, `backups/`
   - `service-account.json`, `client_secret.json`
   - `backend/application-local.properties`, `backend/application.properties`
     (keep only the template)
   - `frontend/.env.local`
   - `.pid`
   - `backend/build/`, `frontend/dist/`, `backend/src/main/resources/static/`
     (generated artifacts)

2. **`README.md` — "Getting Started" section**:
   - Prerequisites (Java 21, Node 20+, pnpm)
   - Clone the repo
   - Run `setup.bat` (or Docker Compose steps)
   - Fill in credentials per `credentials-checklist.md`
   - Run `start.bat`
   - Link to `Build Phases.md` and `High Level Design.md` for deeper context

---

## Delivery Order

| Phase | Effort | Value |
|-------|--------|-------|
| 1 — Bundle frontend | Medium | Eliminates Node runtime, single process |
| 2 — Credential template + validation | Small | Prevents silent failures |
| 3 — Windows scripts | Small | One-command setup and launch |
| 4 — Docker Compose | Medium | Cross-platform alternative |
| 5 — .gitignore + README | Small | Completes the experience |

Start with Phase 1 as it is the foundational change; phases 2, 3, 5 can be
done in parallel after that. Phase 4 is optional and can follow.
