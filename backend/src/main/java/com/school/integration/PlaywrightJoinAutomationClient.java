package com.school.integration;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.InvalidPathException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.ArrayList;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.school.entity.JoinAttemptStatus;
import com.school.model.CalendarEvent;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Playwright-backed {@link JoinAutomationClient} that drives a Chrome browser session to
 * join a Google Meet automatically.
 *
 * <p>Active when {@code app.autojoin.provider=playwright}.
 *
 * <p><strong>Setup requirements (one-time, manual):</strong>
 * <ol>
 *   <li>Install Microsoft Playwright for Java and a Chromium/Chrome browser.</li>
 *   <li>Sign the principal Google account into the Chrome profile at
 *       {@code app.autojoin.chrome-profile-dir}.</li>
 *   <li>Grant Meet permission for camera/microphone inside that profile.</li>
 * </ol>
 *
 * <p>This client launches a persistent Chrome profile, opens the Meet URL, disables media,
 * clicks <i>Join now</i> or <i>Ask to join</i>, and waits for in-call state.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.autojoin.provider", havingValue = "playwright")
public class PlaywrightJoinAutomationClient implements JoinAutomationClient {

    @Value("${app.autojoin.chrome-profile-dir:}")
    private String chromeProfileDir;

    @Value("${app.autojoin.chrome-path:}")
    private String chromePath;

    @Value("${app.autojoin.join-timeout-seconds:30}")
    private int joinTimeoutSeconds;

    @Value("${app.autojoin.retry.max-attempts:3}")
    private int maxAttempts;

    @Value("${app.autojoin.retry.backoff-ms:2000}")
    private long backoffMs;

    @Value("${app.autojoin.require-principal-profile-signed-in:true}")
    private boolean requireProfileSignedIn;

    private Supplier<Playwright> playwrightFactory = Playwright::create;

    private static final Pattern JOIN_NOW_PATTERN =
            Pattern.compile("join now|rejoin|join meeting", Pattern.CASE_INSENSITIVE);
    private static final Pattern ASK_TO_JOIN_PATTERN =
            Pattern.compile("ask to join", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTINUE_WITHOUT_MEDIA_PATTERN =
            Pattern.compile("continue without microphone and camera", Pattern.CASE_INSENSITIVE);

    private static final List<String> IN_CALL_SELECTORS = List.of(
            "button[aria-label*='Leave call']",
            "button[aria-label*='End call']",
            "button:has-text('Leave call')"
    );

    private static final List<String> AUTH_MARKERS = List.of(
            "accounts.google.com", "choose an account", "sign in"
    );
    private static final List<String> PERMISSION_MARKERS = List.of(
            "you can't join this", "not allowed to join", "don't have permission"
    );
    private static final List<String> WAITING_MARKERS = List.of(
            "asking to join", "waiting for someone"
    );

    private static final String JOINED_MESSAGE = "Joined the meeting successfully";
    private static final int MAX_DETAIL_MESSAGE_LENGTH = 400;

    /**
     * Attempts to join the Meet session by launching a Chrome browser with the principal's
     * signed-in profile and navigating to the Meet URL.
     */
    @Override
    public JoinResult attemptJoin(CalendarEvent event) {
        log.info("Playwright join automation invoked for event '{}' (meetLink={})",
                event.getTitle(), event.getMeetLink());
        JoinResult preconditionFailure = validatePreconditions(event);
        if (preconditionFailure != null) {
            return preconditionFailure;
        }

        int attempts = Math.max(1, maxAttempts);
        JoinResult lastResult = new JoinResult(JoinAttemptStatus.FAILED_UNKNOWN, "Auto-join attempt did not run");
        for (int attempt = 1; attempt <= attempts; attempt++) {
            lastResult = attemptOnce(event);
            if (!isRetryable(lastResult.status()) || attempt == attempts) {
                return lastResult;
            }
            log.warn("Auto-join attempt {}/{} failed for '{}' with {}; retrying in {} ms",
                    attempt, attempts, event.getTitle(), lastResult.status(), backoffMs);
            sleepQuietly(backoffMs);
        }
        return lastResult;
    }

    JoinResult validatePreconditions(CalendarEvent event) {
        if (event == null || isBlank(event.getMeetLink())) {
            return new JoinResult(JoinAttemptStatus.FAILED_UNKNOWN,
                    "Event has no Meet link for Playwright automation");
        }
        if (requireProfileSignedIn && isBlank(normalizeConfiguredPath(chromeProfileDir))) {
            return new JoinResult(JoinAttemptStatus.FAILED_AUTH,
                    "app.autojoin.chrome-profile-dir is required when signed-in profile is enforced");
        }
        return null;
    }

    private JoinResult attemptOnce(CalendarEvent event) {
        Path profilePath = resolveProfilePath();
        try (Playwright playwright = playwrightFactory.get();
             BrowserContext context = launchContext(playwright, profilePath)) {
            long timeoutMs = toTimeoutMs(joinTimeoutSeconds);
            context.setDefaultTimeout(timeoutMs);
            Page page = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);

            String meetLink = normalizeMeetLink(event.getMeetLink());
            page.navigate(meetLink, new Page.NavigateOptions().setTimeout((double) timeoutMs));
            clickIfVisible(page, CONTINUE_WITHOUT_MEDIA_PATTERN, 2_000);
            disableMediaIfEnabled(page);

            JoinResult blocker = detectBlockingState(page);
            if (blocker != null) {
                return blocker;
            }

            JoinAction action = clickJoinAction(page, timeoutMs);
            if (action == JoinAction.NONE) {
                return new JoinResult(JoinAttemptStatus.FAILED_UI_NOT_FOUND,
                        "Could not find 'Join now' or 'Ask to join' button");
            }

            boolean joined = waitForInCallState(page, timeoutMs);
            if (joined) {
                return new JoinResult(JoinAttemptStatus.JOINED, JOINED_MESSAGE);
            }

            JoinResult afterActionBlocker = detectBlockingState(page);
            if (afterActionBlocker != null) {
                return afterActionBlocker;
            }

            if (action == JoinAction.ASK_TO_JOIN) {
                return new JoinResult(JoinAttemptStatus.FAILED_WAITING_ROOM_TIMEOUT,
                        "Timed out waiting for host approval after 'Ask to join'");
            }
            return new JoinResult(JoinAttemptStatus.FAILED_UNKNOWN,
                    "Join click completed but in-call state not detected");
        } catch (Exception e) {
            JoinAttemptStatus status = classifyErrorMessage(e.getMessage());
            log.error("Playwright auto-join attempt failed: {}", e.getMessage());
            return new JoinResult(status, safeDetailMessage(e));
        }
    }

    private BrowserContext launchContext(Playwright playwright, Path profilePath) {
        ChromeProfileLaunchTarget target = resolveChromeLaunchTarget(profilePath);
        List<String> launchArgs = new ArrayList<>(List.of(
                "--disable-blink-features=AutomationControlled",
                "--use-fake-ui-for-media-stream",
                "--disable-infobars"
        ));
        if (target.profileDirectoryName() != null) {
            launchArgs.add("--profile-directory=" + target.profileDirectoryName());
        }

        BrowserType.LaunchPersistentContextOptions options = new BrowserType.LaunchPersistentContextOptions()
                .setHeadless(false)
                .setArgs(launchArgs);
        String normalizedChromePath = normalizeConfiguredPath(chromePath);
        if (!isBlank(normalizedChromePath)) {
            options.setExecutablePath(toPathOrThrow("app.autojoin.chrome-path", normalizedChromePath));
        }
        return playwright.chromium().launchPersistentContext(target.userDataDir(), options);
    }

    private JoinAction clickJoinAction(Page page, long timeoutMs) {
        if (clickIfVisible(page, JOIN_NOW_PATTERN, timeoutMs)) {
            return JoinAction.JOIN_NOW;
        }
        if (clickIfVisible(page, ASK_TO_JOIN_PATTERN, timeoutMs)) {
            return JoinAction.ASK_TO_JOIN;
        }
        return JoinAction.NONE;
    }

    private void disableMediaIfEnabled(Page page) {
        clickIfVisible(page, Pattern.compile("turn off microphone", Pattern.CASE_INSENSITIVE), 1_500);
        clickIfVisible(page, Pattern.compile("turn off camera", Pattern.CASE_INSENSITIVE), 1_500);
    }

    private JoinResult detectBlockingState(Page page) {
        String url = nullToEmpty(page.url()).toLowerCase(Locale.ROOT);
        if (containsAny(url, AUTH_MARKERS)) {
            return new JoinResult(JoinAttemptStatus.FAILED_AUTH, "Google sign-in is required in browser profile");
        }
        String content;
        try {
            content = nullToEmpty(page.content()).toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
        if (containsAny(content, AUTH_MARKERS)) {
            return new JoinResult(JoinAttemptStatus.FAILED_AUTH, "Google sign-in is required in browser profile");
        }
        if (containsAny(content, PERMISSION_MARKERS)) {
            return new JoinResult(JoinAttemptStatus.FAILED_PERMISSION, "Meeting access denied by host/domain policy");
        }
        if (containsAny(content, WAITING_MARKERS)) {
            return new JoinResult(JoinAttemptStatus.FAILED_WAITING_ROOM_TIMEOUT,
                    "Still waiting for host approval to join");
        }
        return null;
    }

    private boolean waitForInCallState(Page page, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (String selector : IN_CALL_SELECTORS) {
                if (isVisible(page, selector, 1_000)) {
                    return true;
                }
            }
            sleepQuietly(1_000);
        }
        return false;
    }

    private boolean clickIfVisible(Page page, Pattern buttonNamePattern, long timeoutMs) {
        try {
            Locator locator = page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName(buttonNamePattern)).first();
            if (locator.isVisible(new Locator.IsVisibleOptions().setTimeout((double) timeoutMs))) {
                locator.click(new Locator.ClickOptions().setTimeout((double) timeoutMs));
                return true;
            }
        } catch (Exception ignored) {
            // fallthrough
        }
        return false;
    }

    private boolean isVisible(Page page, String selector, long timeoutMs) {
        try {
            return page.locator(selector).first()
                    .isVisible(new Locator.IsVisibleOptions().setTimeout((double) timeoutMs));
        } catch (Exception ignored) {
            return false;
        }
    }

    static JoinAttemptStatus classifyErrorMessage(String message) {
        String normalized = nullToEmpty(message).toLowerCase(Locale.ROOT);
        if (normalized.contains("err_internet_disconnected")
                || normalized.contains("err_name_not_resolved")
                || normalized.contains("timed out")
                || normalized.contains("timeout")) {
            return JoinAttemptStatus.FAILED_NETWORK;
        }
        if (normalized.contains("choose an account")
                || normalized.contains("sign in")
                || normalized.contains("auth")) {
            return JoinAttemptStatus.FAILED_AUTH;
        }
        if (normalized.contains("permission")
                || normalized.contains("not allowed")
                || normalized.contains("denied")) {
            return JoinAttemptStatus.FAILED_PERMISSION;
        }
        if (normalized.contains("waiting")
                || normalized.contains("ask to join")) {
            return JoinAttemptStatus.FAILED_WAITING_ROOM_TIMEOUT;
        }
        if (normalized.contains("selector")
                || normalized.contains("join now")) {
            return JoinAttemptStatus.FAILED_UI_NOT_FOUND;
        }
        return JoinAttemptStatus.FAILED_UNKNOWN;
    }

    private static boolean isRetryable(JoinAttemptStatus status) {
        return status == JoinAttemptStatus.FAILED_NETWORK
                || status == JoinAttemptStatus.FAILED_UI_NOT_FOUND
                || status == JoinAttemptStatus.FAILED_UNKNOWN;
    }

    private Path resolveProfilePath() {
        String normalizedProfileDir = normalizeConfiguredPath(chromeProfileDir);
        if (isBlank(normalizedProfileDir)) {
            return Paths.get(System.getProperty("java.io.tmpdir"), "schoolmanager-autojoin-profile");
        }
        return toPathOrThrow("app.autojoin.chrome-profile-dir", normalizedProfileDir);
    }

    private static long toTimeoutMs(int timeoutSeconds) {
        return Math.max(5_000L, Math.max(1, timeoutSeconds) * 1_000L);
    }

    private static boolean containsAny(String haystack, List<String> markers) {
        for (String marker : markers) {
            if (haystack.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static String safeDetailMessage(Exception e) {
        String message = nullToEmpty(e.getMessage()).trim();
        if (message.isEmpty()) {
            return e.getClass().getSimpleName();
        }
        if (message.length() > MAX_DETAIL_MESSAGE_LENGTH) {
            return message.substring(0, MAX_DETAIL_MESSAGE_LENGTH);
        }
        return message;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String normalizeConfiguredPath(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return repairLikelyWindowsPath(trimmed);
    }

    private static Path toPathOrThrow(String key, String value) {
        if (looksLikeMalformedWindowsPath(value)) {
            throw new IllegalArgumentException(key + " looks malformed: '" + value + "'. "
                    + "If this value comes from a .properties file on Windows, use forward slashes "
                    + "(e.g. C:/Program Files/Google/Chrome/Application/chrome.exe) or escape backslashes (\\\\).");
        }
        try {
            return Paths.get(value);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException(key + " is not a valid filesystem path: '" + value + "'", e);
        }
    }

    private static boolean looksLikeMalformedWindowsPath(String value) {
        return value != null
                && value.matches("^[A-Za-z]:[^\\\\/].*");
    }

    private static String normalizeMeetLink(String raw) {
        String value = nullToEmpty(raw).trim();
        if (value.isEmpty()) {
            return value;
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value;
        }
        if (value.startsWith("meet.google.com/") || value.startsWith("www.meet.google.com/")) {
            return "https://" + value;
        }
        return value;
    }

    private static String repairLikelyWindowsPath(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        if (!looksLikeMalformedWindowsPath(value)) {
            return value;
        }

        String repaired = value;
        repaired = repaired.replace("Program Files (x86)GoogleChromeApplicationchrome.exe",
                "Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe");
        repaired = repaired.replace("Program FilesGoogleChromeApplicationchrome.exe",
                "Program Files\\Google\\Chrome\\Application\\chrome.exe");
        repaired = repaired.replace("GoogleChromeApplicationchrome.exe",
                "Google\\Chrome\\Application\\chrome.exe");
        repaired = repaired.replace("AppDataLocalGoogleChromeUser Data",
                "AppData\\Local\\Google\\Chrome\\User Data");

        // If there is still no separator immediately after the drive letter, add one.
        if (repaired.matches("^[A-Za-z]:[^\\\\/].*")) {
            repaired = repaired.substring(0, 2) + "\\" + repaired.substring(2);
        }
        return repaired;
    }

    private static ChromeProfileLaunchTarget resolveChromeLaunchTarget(Path configuredPath) {
        if (configuredPath == null) {
            return new ChromeProfileLaunchTarget(null, null);
        }
        Path normalized = configuredPath.normalize();
        Path fileName = normalized.getFileName();
        if (fileName == null) {
            return new ChromeProfileLaunchTarget(normalized, null);
        }
        String lastSegment = fileName.toString();
        if ("Default".equalsIgnoreCase(lastSegment) || lastSegment.matches("(?i)^Profile\\s+\\d+$")) {
            Path parent = normalized.getParent();
            if (parent != null) {
                return new ChromeProfileLaunchTarget(parent, lastSegment);
            }
        }
        return new ChromeProfileLaunchTarget(normalized, null);
    }

    private record ChromeProfileLaunchTarget(Path userDataDir, String profileDirectoryName) {}

    private void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    enum JoinAction {
        JOIN_NOW,
        ASK_TO_JOIN,
        NONE
    }

    void setPlaywrightFactory(Supplier<Playwright> playwrightFactory) {
        this.playwrightFactory = Objects.requireNonNull(playwrightFactory);
    }
}
