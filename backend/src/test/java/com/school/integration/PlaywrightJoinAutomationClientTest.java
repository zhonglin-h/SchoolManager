package com.school.integration;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.school.entity.JoinAttemptStatus;
import com.school.model.CalendarEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlaywrightJoinAutomationClientTest {

    private PlaywrightJoinAutomationClient client;
    private CalendarEvent event;

    @BeforeEach
    void setUp() {
        client = new PlaywrightJoinAutomationClient();
        ReflectionTestUtils.setField(client, "joinTimeoutSeconds", 30);
        ReflectionTestUtils.setField(client, "maxAttempts", 1);
        ReflectionTestUtils.setField(client, "backoffMs", 1L);
        ReflectionTestUtils.setField(client, "requireProfileSignedIn", true);
        ReflectionTestUtils.setField(client, "chromeProfileDir", "C:/Users/principal/AppData/Local/ChromeMeetProfile");
        ReflectionTestUtils.setField(client, "chromePath", "");

        event = new CalendarEvent(
                "evt-1",
                "Math Class",
                "https://meet.google.com/abc-defg-hij",
                "abc-defg-hij",
                LocalDateTime.now().plusMinutes(1),
                LocalDateTime.now().plusMinutes(61),
                List.of()
        );
    }

    // ── classifyErrorMessage ────────────────────────────────────────────────

    @Test
    void classifyErrorMessage_mapsNetwork() {
        assertThat(PlaywrightJoinAutomationClient.classifyErrorMessage("net::ERR_INTERNET_DISCONNECTED"))
                .isEqualTo(JoinAttemptStatus.FAILED_NETWORK);
    }

    @Test
    void classifyErrorMessage_mapsAuth() {
        assertThat(PlaywrightJoinAutomationClient.classifyErrorMessage("Choose an account to sign in"))
                .isEqualTo(JoinAttemptStatus.FAILED_AUTH);
    }

    @Test
    void classifyErrorMessage_mapsPermission() {
        assertThat(PlaywrightJoinAutomationClient.classifyErrorMessage("Permission denied by host"))
                .isEqualTo(JoinAttemptStatus.FAILED_PERMISSION);
    }

    @Test
    void classifyErrorMessage_mapsUiNotFound() {
        assertThat(PlaywrightJoinAutomationClient.classifyErrorMessage("selector for Join now not found"))
                .isEqualTo(JoinAttemptStatus.FAILED_UI_NOT_FOUND);
    }

    // ── precondition guards ─────────────────────────────────────────────────

    @Test
    void attemptJoin_returnsFailedAuthWhenSignedInProfileRequiredAndMissing() {
        ReflectionTestUtils.setField(client, "chromeProfileDir", "");

        JoinResult result = client.attemptJoin(event);

        assertThat(result.status()).isEqualTo(JoinAttemptStatus.FAILED_AUTH);
        assertThat(result.detailMessage()).contains("chrome-profile-dir");
    }

    @Test
    void attemptJoin_returnsFailedUnknownWhenMeetLinkMissing() {
        event.setMeetLink("  ");

        JoinResult result = client.attemptJoin(event);

        assertThat(result.status()).isEqualTo(JoinAttemptStatus.FAILED_UNKNOWN);
        assertThat(result.detailMessage()).contains("no Meet link");
    }

    @Test
    void attemptJoin_classifiesFactoryFailureAsNetwork() {
        client.setPlaywrightFactory(() -> {
            throw new RuntimeException("timeout while opening browser");
        });

        JoinResult result = client.attemptJoin(event);

        assertThat(result.status()).isEqualTo(JoinAttemptStatus.FAILED_NETWORK);
    }

    // ── persistent runner: context reuse ────────────────────────────────────

    @Test
    void getOrCreateContext_createsContextOnFirstCall() {
        AtomicInteger factoryCalls = new AtomicInteger();
        Playwright pw = mock(Playwright.class);
        BrowserContext ctx = mock(BrowserContext.class);
        when(ctx.pages()).thenReturn(List.of()); // isContextAlive returns true

        client.setPlaywrightFactory(countingFactory(factoryCalls, pw, ctx));

        client.getOrCreateContext();

        assertThat(factoryCalls.get()).isEqualTo(1);
    }

    @Test
    void getOrCreateContext_reusesContextOnSubsequentCalls() {
        AtomicInteger factoryCalls = new AtomicInteger();
        Playwright pw = mock(Playwright.class);
        BrowserContext ctx = mock(BrowserContext.class);
        when(ctx.pages()).thenReturn(List.of()); // always alive

        client.setPlaywrightFactory(countingFactory(factoryCalls, pw, ctx));

        BrowserContext first  = client.getOrCreateContext();
        BrowserContext second = client.getOrCreateContext();
        BrowserContext third  = client.getOrCreateContext();

        assertThat(factoryCalls.get())
                .as("playwright factory should be called only once when context stays alive")
                .isEqualTo(1);
        assertThat(second).isSameAs(first);
        assertThat(third).isSameAs(first);
    }

    @Test
    void getOrCreateContext_recreatesContextWhenPreviousContextIsDead() {
        AtomicInteger factoryCalls = new AtomicInteger();
        Playwright pw1 = mock(Playwright.class);
        Playwright pw2 = mock(Playwright.class);
        BrowserContext deadCtx  = mock(BrowserContext.class);
        BrowserContext freshCtx = mock(BrowserContext.class);

        // deadCtx throws on pages() → isContextAlive = false
        when(deadCtx.pages()).thenThrow(new RuntimeException("target closed"));
        when(freshCtx.pages()).thenReturn(List.of());

        // First call returns pw1/deadCtx, second call returns pw2/freshCtx
        AtomicInteger call = new AtomicInteger();
        client.setPlaywrightFactory(() -> {
            factoryCalls.incrementAndGet();
            if (call.incrementAndGet() == 1) {
                configureContextLaunch(pw1, deadCtx);
                return pw1;
            }
            configureContextLaunch(pw2, freshCtx);
            return pw2;
        });

        // First creation: gets deadCtx
        BrowserContext first = client.getOrCreateContext();
        assertThat(first).isSameAs(deadCtx);

        // deadCtx.pages() throws → isContextAlive = false → recreates on next call
        BrowserContext second = client.getOrCreateContext();
        assertThat(second).isSameAs(freshCtx);
        assertThat(factoryCalls.get()).isEqualTo(2);
    }

    @Test
    void shutdown_closesSharedContextAndPlaywright() throws Exception {
        Playwright pw = mock(Playwright.class);
        BrowserContext ctx = mock(BrowserContext.class);
        when(ctx.pages()).thenReturn(List.of());

        client.setPlaywrightFactory(countingFactory(new AtomicInteger(), pw, ctx));
        client.getOrCreateContext(); // initialise shared state

        client.shutdown();

        verify(ctx, times(1)).close();
        verify(pw, times(1)).close();
    }

    @Test
    void shutdown_isIdempotentWhenNoBrowserCreated() {
        // Should not throw even though no context was ever created
        client.shutdown();
        client.shutdown();
    }

    // ── concurrent meetings ─────────────────────────────────────────────────

    @Test
    void getOrCreateContext_concurrentCallsCreateContextOnce() throws Exception {
        int threadCount = 8;
        AtomicInteger factoryCalls = new AtomicInteger();
        Playwright pw = mock(Playwright.class);
        BrowserContext ctx = mock(BrowserContext.class);
        when(ctx.pages()).thenReturn(List.of()); // always alive

        client.setPlaywrightFactory(countingFactory(factoryCalls, pw, ctx));

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<BrowserContext>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                startLatch.await();
                return client.getOrCreateContext();
            }));
        }

        startLatch.countDown();

        List<BrowserContext> results = new ArrayList<>();
        for (Future<BrowserContext> f : futures) {
            results.add(f.get(10, TimeUnit.SECONDS));
        }
        executor.shutdown();

        assertThat(factoryCalls.get())
                .as("playwright factory must be called exactly once when all threads race simultaneously")
                .isEqualTo(1);
        assertThat(results)
                .hasSize(threadCount)
                .allMatch(c -> c == ctx, "every thread must receive the same shared context");
    }

    @Test
    void attemptJoin_concurrentCallsForDifferentMeetingsGetIndependentResultsWithoutDeadlock()
            throws Exception {
        // Both attempts fail at context creation so the navigation path is bypassed.
        // This verifies: no deadlock, independent per-call results, and clean shared state
        // after concurrent failures.
        client.setPlaywrightFactory(() -> {
            throw new RuntimeException("timeout while opening browser");
        });

        CalendarEvent eventA = meetingEvent("evt-A", "https://meet.google.com/aaa-bbbb-ccc");
        CalendarEvent eventB = meetingEvent("evt-B", "https://meet.google.com/ddd-eeee-fff");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Future<JoinResult> futureA = executor.submit(() -> {
            startLatch.await();
            return client.attemptJoin(eventA);
        });
        Future<JoinResult> futureB = executor.submit(() -> {
            startLatch.await();
            return client.attemptJoin(eventB);
        });

        startLatch.countDown();

        JoinResult resultA = futureA.get(15, TimeUnit.SECONDS);
        JoinResult resultB = futureB.get(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(resultA.status())
                .as("meeting A should fail with network error from factory timeout")
                .isEqualTo(JoinAttemptStatus.FAILED_NETWORK);
        assertThat(resultB.status())
                .as("meeting B should fail with network error from factory timeout")
                .isEqualTo(JoinAttemptStatus.FAILED_NETWORK);

        // Both failures must have invalidated shared state so the next join attempt
        // triggers a fresh browser launch.
        assertThat(ReflectionTestUtils.getField(client, "sharedContext"))
                .as("sharedContext must be null after concurrent failures")
                .isNull();
        assertThat(ReflectionTestUtils.getField(client, "sharedPlaywright"))
                .as("sharedPlaywright must be null after concurrent failures")
                .isNull();
    }

    // ── disableMediaIfEnabled ───────────────────────────────────────────────

    @Test
    void disableMediaIfEnabled_returnsNullWhenNeitherButtonVisible() {
        Page page = mock(Page.class);
        Locator locator = mock(Locator.class);
        when(page.getByRole(any(), any())).thenReturn(locator);
        when(locator.first()).thenReturn(locator);
        when(locator.isVisible(any())).thenReturn(false);

        assertThat(client.disableMediaIfEnabled(page)).isNull();
    }

    @Test
    void disableMediaIfEnabled_returnsFailedMediaDisableWhenMicRemainsOnAfterClick() {
        Page page = mock(Page.class);
        Locator locator = mock(Locator.class);
        when(page.getByRole(any(), any())).thenReturn(locator);
        when(locator.first()).thenReturn(locator);
        // mic click: visible; camera click: visible; mic re-check: still visible → fail
        when(locator.isVisible(any())).thenReturn(true);

        JoinResult result = client.disableMediaIfEnabled(page);

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(JoinAttemptStatus.FAILED_MEDIA_DISABLE);
        assertThat(result.detailMessage()).containsIgnoringCase("microphone");
    }

    @Test
    void disableMediaIfEnabled_returnsNullWhenMicTurnsOffAfterClick() {
        Page page = mock(Page.class);
        Locator micLocator = mock(Locator.class);
        Locator camLocator = mock(Locator.class);
        // mic call, camera call, mic re-check call
        when(page.getByRole(any(), any())).thenReturn(micLocator, camLocator, micLocator);
        when(micLocator.first()).thenReturn(micLocator);
        when(camLocator.first()).thenReturn(camLocator);
        when(micLocator.isVisible(any())).thenReturn(true, false); // on → click → off
        when(camLocator.isVisible(any())).thenReturn(false);        // already off

        assertThat(client.disableMediaIfEnabled(page)).isNull();
    }

    @Test
    void disableMediaIfEnabled_returnsFailedMediaDisableWhenCameraRemainsOnAfterClick() {
        Page page = mock(Page.class);
        Locator micLocator = mock(Locator.class);
        Locator camLocator = mock(Locator.class);
        // mic call, camera call, camera re-check call (mic re-check skipped since micWasOn=false)
        when(page.getByRole(any(), any())).thenReturn(micLocator, camLocator, camLocator);
        when(micLocator.first()).thenReturn(micLocator);
        when(camLocator.first()).thenReturn(camLocator);
        when(micLocator.isVisible(any())).thenReturn(false);  // already off
        when(camLocator.isVisible(any())).thenReturn(true);   // on → click → still on → fail

        JoinResult result = client.disableMediaIfEnabled(page);

        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(JoinAttemptStatus.FAILED_MEDIA_DISABLE);
        assertThat(result.detailMessage()).containsIgnoringCase("camera");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** Returns a factory that increments a counter and then configures the supplied pw/ctx. */
    private Supplier<Playwright> countingFactory(AtomicInteger counter, Playwright pw,
                                                  BrowserContext ctx) {
        return () -> {
            counter.incrementAndGet();
            configureContextLaunch(pw, ctx);
            return pw;
        };
    }

    /**
     * Configures {@code pw} so that {@code pw.chromium().launchPersistentContext(...)} returns
     * {@code ctx} — the minimal stub needed by {@link PlaywrightJoinAutomationClient#getOrCreateContext()}.
     */
    private void configureContextLaunch(Playwright pw, BrowserContext ctx) {
        com.microsoft.playwright.BrowserType chromium = mock(com.microsoft.playwright.BrowserType.class);
        when(pw.chromium()).thenReturn(chromium);
        when(chromium.launchPersistentContext(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(ctx);
    }

    @Test
    void attemptJoin_serializesConcurrentPlaywrightUsageForSharedContext() throws Exception {
        Playwright pw = mock(Playwright.class);
        BrowserContext ctx = mock(BrowserContext.class);
        when(ctx.pages()).thenReturn(List.of()); // always alive
        configureContextLaunch(pw, ctx);
        client.setPlaywrightFactory(() -> pw);

        // Prime the shared context so concurrent attempts race inside attemptOnce/newPage.
        client.getOrCreateContext();

        AtomicInteger inNewPage = new AtomicInteger();
        AtomicInteger maxInNewPage = new AtomicInteger();
        CountDownLatch overlapLatch = new CountDownLatch(2);
        when(ctx.newPage()).thenAnswer(invocation -> {
            int concurrent = inNewPage.incrementAndGet();
            maxInNewPage.updateAndGet(prev -> Math.max(prev, concurrent));
            overlapLatch.countDown();
            // If concurrent calls are not serialized, both threads overlap here.
            overlapLatch.await(200, TimeUnit.MILLISECONDS);
            inNewPage.decrementAndGet();
            throw new RuntimeException("simulated newPage failure");
        });

        CalendarEvent eventA = meetingEvent("evt-A2", "https://meet.google.com/aaa-bbbb-ccc");
        CalendarEvent eventB = meetingEvent("evt-B2", "https://meet.google.com/ddd-eeee-fff");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        Future<JoinResult> futureA = executor.submit(() -> {
            startLatch.await();
            return client.attemptJoin(eventA);
        });
        Future<JoinResult> futureB = executor.submit(() -> {
            startLatch.await();
            return client.attemptJoin(eventB);
        });

        startLatch.countDown();

        JoinResult resultA = futureA.get(15, TimeUnit.SECONDS);
        JoinResult resultB = futureB.get(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(resultA.status()).isEqualTo(JoinAttemptStatus.FAILED_UNKNOWN);
        assertThat(resultB.status()).isEqualTo(JoinAttemptStatus.FAILED_UNKNOWN);
        assertThat(maxInNewPage.get())
                .as("shared BrowserContext.newPage() must never be used concurrently")
                .isEqualTo(1);
    }

    private CalendarEvent meetingEvent(String id, String meetLink) {
        return new CalendarEvent(
                id,
                "Test Meeting " + id,
                meetLink,
                id + "-space",
                LocalDateTime.now().plusMinutes(1),
                LocalDateTime.now().plusMinutes(61),
                List.of()
        );
    }
}
