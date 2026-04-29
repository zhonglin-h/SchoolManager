# Credentials Checklist

Before starting School Manager for the first time, complete every step below.
Items that are already done can be checked off.

---

## 1. Google Cloud Project

1. Go to [Google Cloud Console](https://console.cloud.google.com/).
2. Create a new project (or select an existing one).
3. Enable the following APIs:
   - **Google Calendar API**
   - **Google Meet API**

---

## 2. OAuth 2.0 Client Credentials (`client_secret.json`)

1. In Cloud Console → **APIs & Services → Credentials**, click **Create Credentials → OAuth client ID**.
2. Application type: **Desktop app**.
3. Download the JSON file.
4. **Save it as `client_secret.json` in the repository root** (next to `README.md`).
   - The file is gitignored and will never be committed.
5. If the OAuth consent screen status is **Testing**, add your principal account as a test user.

---

## 3. `application-local.properties`

1. Copy `application.properties.template` to  
   `backend/src/main/resources/application-local.properties`.
2. Fill in every `<placeholder>` value:

| Property | Where to find it |
|----------|-----------------|
| `google.calendar.id` | The Google Calendar email/ID you want to monitor |
| `app.principal.name` | Your name |
| `app.principal.email` | Your Google account email |
| `app.principal.google-user-id` | [People API explorer](https://developers.google.com/people/api/rest/v1/people/get) — call `people/me` |
| `spring.mail.username` | Your Gmail address |
| `spring.mail.password` | A Gmail **App Password** (see below) |

---

## 4. Gmail App Password

1. Go to [myaccount.google.com](https://myaccount.google.com) → **Security**.
2. Under "How you sign in to Google", open **2-Step Verification** (must be enabled).
3. Scroll to **App passwords** and generate one for "School Manager".
4. Copy the 16-character password into `spring.mail.password` in your  
   `application-local.properties`.

---

## 5. (Optional) Playwright Auto-Join

If you want the app to automatically join Google Meet sessions:

1. Create a dedicated Chrome profile:
   ```
   "C:/Program Files/Google/Chrome/Application/chrome.exe" --user-data-dir="C:/chrome-autojoin/UserData"
   ```
2. Sign in as the principal account in that Chrome window and grant Meet mic/camera permissions once.
3. Close Chrome.
4. In `application-local.properties`, set:
   - `app.autojoin.enabled=true`
   - `app.autojoin.chrome-profile-dir=C:/chrome-autojoin/UserData/Profile 1`
   - `app.autojoin.chrome-path=C:/Program Files/Google/Chrome/Application/chrome.exe`

---

## 6. First Run

1. Run `setup.bat` (Windows) or `docker compose up --build` (Docker).
2. On the very first start the app opens a browser window for Google OAuth consent.  
   Approve the requested scopes.
3. Tokens are cached in `./data/tokens/` and reused on subsequent starts.

---

## Quick File Placement Summary

| File | Location |
|------|----------|
| `client_secret.json` | Repository root (`./client_secret.json`) |
| `application-local.properties` | `backend/src/main/resources/application-local.properties` |
| Service-account JSON (if used) | `./service-account.json` (repository root) |
