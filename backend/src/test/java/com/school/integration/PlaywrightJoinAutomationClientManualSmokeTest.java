package com.school.integration;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.school.entity.JoinAttemptStatus;
import com.school.model.CalendarEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("manual")
@SpringJUnitConfig(PlaywrightJoinAutomationClientManualSmokeTest.TestConfig.class)
@TestPropertySource(
        locations = {
                "classpath:application-local.properties",
                "classpath:manual-smoke.local.properties"
        }
)
class PlaywrightJoinAutomationClientManualSmokeTest {
    private static final long MANUAL_CLOSE_TIMEOUT_MS = 90_000L;
    private static final long EMPTY_PAGES_FORCE_CLOSE_GRACE_MS = 5_000L;

    @Autowired
    private PlaywrightJoinAutomationClient client;

    @Autowired
    private Environment environment;

    @Configuration
    static class TestConfig {
        @Bean
        PlaywrightJoinAutomationClient playwrightJoinAutomationClient() {
            return new PlaywrightJoinAutomationClient();
        }
    }

    @BeforeEach
    void configureClientFromProperties() {
        ReflectionTestUtils.setField(client, "chromeProfileDir",
                normalize(environment.getProperty("app.autojoin.chrome-profile-dir", "")));
        ReflectionTestUtils.setField(client, "chromePath",
                normalize(environment.getProperty("app.autojoin.chrome-path", "")));
        ReflectionTestUtils.setField(client, "joinTimeoutSeconds",
                environment.getProperty("app.autojoin.join-timeout-seconds", Integer.class, 30));
        ReflectionTestUtils.setField(client, "maxAttempts",
                environment.getProperty("app.autojoin.retry.max-attempts", Integer.class, 3));
        ReflectionTestUtils.setField(client, "backoffMs",
                environment.getProperty("app.autojoin.retry.backoff-ms", Long.class, 2_000L));
        ReflectionTestUtils.setField(client, "requireProfileSignedIn",
                environment.getProperty("app.autojoin.require-principal-profile-signed-in", Boolean.class, true));
    }

    @Test
    void attemptJoin_realBrowserSmokeTest() {
        String meetLink = firstNonBlank(normalize(environment.getProperty("smoke.meet.link")),
                normalize(System.getenv("SMOKE_MEET_LINK")));
        String effectiveProfileDir = firstNonBlank(
                normalize(environment.getProperty("app.autojoin.chrome-profile-dir")),
                normalize(System.getenv("SMOKE_CHROME_PROFILE_DIR")));

        assumeTrue(meetLink != null && !meetLink.isBlank(),
                "Set SMOKE_MEET_LINK to a real Google Meet URL");
        assumeTrue(effectiveProfileDir != null && !effectiveProfileDir.isBlank(),
                "Set SMOKE_CHROME_PROFILE_DIR to a signed-in Chrome profile directory");

        CalendarEvent event = manualEvent("smoke-1", "Manual Playwright Smoke", meetLink);

        JoinResult result = client.attemptJoin(event);

        assertJoined(result, "initial smoke join");
    }

    @Test
    void attemptJoin_recoversAfterManualWindowClose() {
        String meetLink = firstNonBlank(normalize(environment.getProperty("smoke.meet.link")),
                normalize(System.getenv("SMOKE_MEET_LINK")));
        String effectiveProfileDir = firstNonBlank(
                normalize(environment.getProperty("app.autojoin.chrome-profile-dir")),
                normalize(System.getenv("SMOKE_CHROME_PROFILE_DIR")));

        assumeTrue(meetLink != null && !meetLink.isBlank(),
                "Set SMOKE_MEET_LINK to a real Google Meet URL");
        assumeTrue(effectiveProfileDir != null && !effectiveProfileDir.isBlank(),
                "Set SMOKE_CHROME_PROFILE_DIR to a signed-in Chrome profile directory");

        CalendarEvent firstAttemptEvent = manualEvent(
                "smoke-close-recover-1",
                "Manual Playwright Close/Recover - First Join",
                meetLink
        );
        JoinResult firstResult = client.attemptJoin(firstAttemptEvent);
        assertJoined(firstResult, "first join before manual close");

        BrowserContext firstContext = client.getOrCreateContext();
        waitForManualWindowClose(firstContext);

        CalendarEvent secondAttemptEvent = manualEvent(
                "smoke-close-recover-2",
                "Manual Playwright Close/Recover - Retry Join",
                meetLink
        );
        JoinResult secondResult = client.attemptJoin(secondAttemptEvent);
        assertJoined(secondResult, "second join after manual close");

        BrowserContext recoveredContext = client.getOrCreateContext();
        assertThat(recoveredContext).as("recreated browser context should not be the previously closed one")
                .isNotSameAs(firstContext);
    }

    private CalendarEvent manualEvent(String id, String title, String meetLink) {
        LocalDateTime start = LocalDateTime.now().plusMinutes(1);
        return new CalendarEvent(
                id,
                title,
                meetLink,
                "manual-space-code",
                start,
                start.plusMinutes(60),
                List.of()
        );
    }

    private void waitForManualWindowClose(BrowserContext context) {
        AtomicBoolean closeEventSeen = new AtomicBoolean(false);
        try {
            if (context != null) {
                context.onClose(closedContext -> closeEventSeen.set(true));
            }
        } catch (Exception e) {
            System.out.println("Could not attach BrowserContext.onClose listener: " + summarizeException(e));
        }

        System.out.println();
        System.out.println("MANUAL STEP REQUIRED:");
        System.out.println("Close all Playwright-controlled Chrome windows now (not just the Meet tab).");
        System.out.println("Waiting up to " + (MANUAL_CLOSE_TIMEOUT_MS / 1000) + " seconds for browser close...");

        long deadline = System.currentTimeMillis() + MANUAL_CLOSE_TIMEOUT_MS;
        long nextProgressLogAt = System.currentTimeMillis() + 10_000L;
        long emptyPagesSince = -1L;
        while (System.currentTimeMillis() < deadline) {
            CloseObservation observation = inspectContextClose(context, closeEventSeen);
            if (observation.closed()) {
                System.out.println("Detected browser close (" + observation.reason() + "). Continuing recovery attempt...");
                return;
            }
            long now = System.currentTimeMillis();
            if (observation.pageCount() != null && observation.pageCount() == 0) {
                if (emptyPagesSince < 0) {
                    emptyPagesSince = now;
                    System.out.println("No pages remain in context. Waiting briefly for close signal...");
                } else if (now - emptyPagesSince >= EMPTY_PAGES_FORCE_CLOSE_GRACE_MS) {
                    System.out.println("No pages remained for " + (EMPTY_PAGES_FORCE_CLOSE_GRACE_MS / 1000)
                            + "s without a close signal; force-closing context to continue recovery test.");
                    forceCloseContext(context);
                    System.out.println("Forced context close completed. Continuing recovery attempt...");
                    return;
                }
            } else {
                emptyPagesSince = -1L;
            }

            if (now >= nextProgressLogAt) {
                long remainingSeconds = Math.max(0L, (deadline - now) / 1000L);
                String pageInfo = observation.pageCount() == null ? "unknown pages" : (observation.pageCount() + " open page(s)");
                System.out.println("Still waiting for browser close... " + remainingSeconds + "s remaining (" + pageInfo + ").");
                nextProgressLogAt = now + 10_000L;
            }
            sleepQuietly(500);
        }

        throw new AssertionError(
                "Did not detect manual browser close within " + (MANUAL_CLOSE_TIMEOUT_MS / 1000)
                        + " seconds. Close all windows for the automated browser profile and rerun.");
    }

    private CloseObservation inspectContextClose(BrowserContext context, AtomicBoolean closeEventSeen) {
        if (context == null) {
            return new CloseObservation(true, "context reference is null", null);
        }
        if (closeEventSeen.get()) {
            return new CloseObservation(true, "BrowserContext.onClose fired", null);
        }
        try {
            if (context.isClosed()) {
                return new CloseObservation(true, "BrowserContext.isClosed() returned true", null);
            }
        } catch (Exception e) {
            return new CloseObservation(true, "BrowserContext.isClosed() threw " + summarizeException(e), null);
        }

        try {
            Browser browser = context.browser();
            if (browser != null && !browser.isConnected()) {
                return new CloseObservation(true, "Browser.isConnected() returned false", null);
            }
        } catch (Exception e) {
            return new CloseObservation(true, "Browser.isConnected() probe threw " + summarizeException(e), null);
        }

        try {
            int pageCount = context.pages().size();
            return new CloseObservation(false, "", pageCount);
        } catch (Exception e) {
            return new CloseObservation(true, "BrowserContext.pages() probe threw " + summarizeException(e), null);
        }
    }

    private void forceCloseContext(BrowserContext context) {
        if (context == null) {
            return;
        }
        try {
            context.close();
        } catch (Exception e) {
            throw new AssertionError("Failed to force-close browser context after manual close step: "
                    + summarizeException(e), e);
        }
    }

    private String summarizeException(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return e.getClass().getSimpleName() + ": " + message;
    }

    private record CloseObservation(boolean closed, String reason, Integer pageCount) {}

    private void assertJoined(JoinResult result, String phase) {
        assertThat(result).as("%s result should exist", phase).isNotNull();
        assertThat(result.status())
                .withFailMessage("Expected JOINED in %s but was %s. Detail: %s",
                        phase, result.status(), result.detailMessage())
                .isEqualTo(JoinAttemptStatus.JOINED);
        assertThat(result.detailMessage()).as("%s detail message", phase).isNotBlank();
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for manual browser close", e);
        }
    }

    private static String firstNonBlank(String first, String fallback) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return fallback;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }
}
