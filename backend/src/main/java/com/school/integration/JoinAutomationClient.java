package com.school.integration;

import com.school.model.CalendarEvent;

/**
 * Abstraction over the browser-automation layer that physically joins a Google Meet session.
 * <p>
 * Implementations are selected via {@code app.autojoin.provider}:
 * <ul>
 *   <li>{@link NoopJoinAutomationClient} — used when automation is disabled or no provider
 *       is configured; always returns immediately without opening a browser.</li>
 *   <li>{@link PlaywrightJoinAutomationClient} — selected when
 *       {@code app.autojoin.provider=playwright}; drives a Chrome session on the local machine.</li>
 * </ul>
 */
public interface JoinAutomationClient {

    /**
     * Attempts to join the Google Meet session associated with the given calendar event.
     * <p>
     * Implementations must be idempotent for a single call — if the session is already
     * joined, they should return {@code JOINED} rather than failing.
     *
     * @param event the calendar event whose Meet URL should be joined
     * @return the terminal outcome of the join attempt; never {@code null}
     */
    JoinResult attemptJoin(CalendarEvent event);
}
