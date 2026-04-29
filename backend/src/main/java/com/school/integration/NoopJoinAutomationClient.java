package com.school.integration;

import com.school.entity.JoinAttemptStatus;
import com.school.model.CalendarEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * No-op {@link JoinAutomationClient} used when auto-join automation is disabled or no
 * provider is configured.  Returns immediately without opening a browser.
 * <p>
 * Active whenever {@link PlaywrightJoinAutomationClient} is not registered
 * (i.e. {@code app.autojoin.provider} is not {@code playwright}).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.autojoin.provider", havingValue = "noop", matchIfMissing = true)
public class NoopJoinAutomationClient implements JoinAutomationClient {

    @Override
    public JoinResult attemptJoin(CalendarEvent event) {
        log.debug("Noop join automation: skipping join for event '{}'", event.getTitle());
        return new JoinResult(JoinAttemptStatus.FAILED_UNKNOWN,
                "Auto-join provider not configured (noop)");
    }
}
