# Plan: Principal Real-Time Notifications (Phase 1)

## Context
Principal attendance notifications (ARRIVAL, ALL_PRESENT, LATE, ABSENT) are real-time events better delivered as a push/text than email. The principal is a single known user who controls the app, so they can use any messaging client — they are not constrained to SMS like a parent would be. This is important because cheap or free alternatives to paid SMS exist.

`NotificationChannel` already has `EMAIL` and `SMS`. `Student.parentPhone` already exists.

---

## Messaging provider options

These are all push-to-phone channels. The principal receives the notification; no action needed from parents here.

### Traditional SMS
| Provider | Price (US, 30/day) | Java SDK | Setup |
|---|---|---|---|
| Twilio | ~$7/month | Official SDK | Account + verified number |
| Telnyx | ~$3.60/month | Official SDK | Account + number |
| Vonage | ~$7/month | Official SDK | Account + number |
| AWS SNS | ~$5.80/month | AWS SDK (verbose) | AWS account + IAM |

All paid SMS providers require purchasing a phone number (~$1–2/month extra).

### Free / app-based alternatives
| Provider | Cost | Java integration | Constraint |
|---|---|---|---|
| **Telegram Bot API** | **Free** | Plain HTTP — no SDK needed | Principal must use Telegram |
| WhatsApp Business (Meta Cloud API) | Free ≤1000 msgs/month | HTTP REST | Requires Meta business account + phone verification |
| WeChat | Free for account holders | Complex XML/OAuth API | Requires Chinese business verification; impractical here |
| Signal | No official API | — | No bot/automation support |

### Recommendation: Telegram Bot API
Since only the **principal** (one person, the app owner) needs to receive these notifications, the app-based constraint is not a problem — the principal can simply install Telegram. The Telegram Bot API:
- Is completely **free** at any volume
- Uses a simple **HTTPS REST call** (`sendMessage`) — no SDK or dependency needed, just `RestTemplate` or `WebClient`
- Takes ~10 minutes to set up (create bot via BotFather → get chat_id)
- Works on iOS, Android, macOS, Windows, and web

**If the principal strongly prefers not to install another app**, Telnyx is the cheapest paid SMS option (~$3.60/month at 30/day).

---

## Revised notification channel matrix

| Type | Principal | Parent |
|---|---|---|
| MEETING_NOT_STARTED_15 | Email | — |
| NOT_YET_JOINED_3 | Email | Email |
| ARRIVAL | **Telegram** | — |
| ALL_PRESENT | **Telegram** | — |
| LATE | **Telegram** | Email |
| ABSENT | **Telegram** (add toPrincipal=true) | Email |

---

## Critical files to modify

| File | Change |
|---|---|
| `backend/build.gradle` | No new dependency (Telegram uses plain HTTP) |
| `backend/src/main/resources/application.properties` | Add `telegram.bot-token` + `telegram.chat-id` |
| `backend/src/main/java/com/school/integration/TelegramClient.java` | **New file** |
| `backend/src/main/java/com/school/service/NotificationType.java` | Add `principalViaTelegram` field |
| `backend/src/main/java/com/school/service/NotificationService.java` | Inject TelegramClient, add Telegram path |
| `backend/src/main/java/com/school/repository/NotificationLogRepository.java` | Add channel to dedup queries |
| `backend/src/test/java/com/school/service/NotificationServiceTest.java` | Add Telegram test cases |

*(If SMS is chosen instead, replace `TelegramClient` with `SmsClient` and the Twilio/Telnyx SDK; all other steps are identical.)*

---

## Steps (assuming Telegram)

### 1. One-time Telegram setup (principal does this once)
1. Message `@BotFather` on Telegram → `/newbot` → copy the **bot token**
2. Message the new bot once → call `https://api.telegram.org/bot<TOKEN>/getUpdates` → copy the **chat_id**
3. Add both to `application-local.properties`

### 2. Add configuration — `application.properties`
```properties
telegram.bot-token=
telegram.chat-id=
```

### 3. Create `TelegramClient`
`backend/src/main/java/com/school/integration/TelegramClient.java`

- `@Component` with `@Value`-injected `botToken`, `chatId`
- Inject Spring's `RestTemplate`
- `send(String message)` — HTTP POST to `https://api.telegram.org/bot{token}/sendMessage` with JSON body `{"chat_id": "...", "text": "..."}`
- Throws on non-2xx response so caller can catch and log failure

No new Gradle dependency needed — `RestTemplate` is already available via `spring-boot-starter-web`.

### 4. Add `principalViaTelegram` to `NotificationType`
`backend/src/main/java/com/school/service/NotificationType.java`

- New `final boolean principalViaTelegram` field + constructor parameter
- Set per type:
  - `MEETING_NOT_STARTED_15` → `false`
  - `NOT_YET_JOINED_3` → `false`
  - `ARRIVAL` → `true`
  - `ALL_PRESENT` → `true`
  - `LATE` → `true`
  - `ABSENT` → `true` **and change `toPrincipal` from `false` to `true`**

### 5. Fix deduplication to be per-channel — `NotificationLogRepository`
The current dedup query doesn't include channel. Add channel-aware variants:

```java
boolean existsByStudentIdAndCalendarEventIdAndDateAndTypeAndChannelAndSuccessTrue(
    Long studentId, String calendarEventId, LocalDate date, String type, NotificationChannel channel);

boolean existsByCalendarEventIdAndDateAndTypeAndChannelAndStudentIsNullAndSuccessTrue(
    String calendarEventId, LocalDate date, String type, NotificationChannel channel);
```

Also update `NotificationChannel` enum: rename `SMS` → `TELEGRAM` (or add `TELEGRAM` alongside `SMS` for future use).

### 6. Update `NotificationService.notify()`
`backend/src/main/java/com/school/service/NotificationService.java`

- Inject `TelegramClient telegramClient`
- Replace the single dedup + single log with **two independent paths**:

  **Email path** (unchanged for non-Telegram types):
  - `toPrincipal && !type.principalViaTelegram` → email principal
  - `toParent && student.parentEmail non-blank` → email parent
  - Save log with `channel = EMAIL`

  **Telegram path:**
  - `toPrincipal && type.principalViaTelegram` → `telegramClient.send(body)`
  - Save log with `channel = TELEGRAM`
  - Each path checks its own per-channel dedup, catches exceptions independently, writes its own log entry

### 7. Tests — `NotificationServiceTest.java`
- Mock `TelegramClient` alongside `EmailClient`
- **Telegram types (ARRIVAL, ALL_PRESENT, LATE, ABSENT):** assert `telegramClient.send()` called, `emailClient.send()` NOT called for principal
- **Email types (MEETING_NOT_STARTED_15, NOT_YET_JOINED_3):** assert `emailClient.send()` called for principal, `telegramClient.send()` never called
- **ABSENT:** principal now gets Telegram; parent still gets email
- **Per-channel dedup:** existing Telegram log doesn't block email resend (and vice versa)
- **Telegram failure:** exception caught, logged with `success=false`, does not prevent email log

---

## Verification
1. Complete one-time Telegram setup; add token + chat_id to `application-local.properties`
2. Set `app.notifications.enabled=true`
3. Trigger an ARRIVAL or ABSENT (via mock meet)
4. Confirm Telegram message appears on principal's phone
5. Check H2 console: ARRIVAL rows have `channel = TELEGRAM`; MEETING_NOT_STARTED_15 rows have `channel = EMAIL`
6. Run `./gradlew test` — all tests green
