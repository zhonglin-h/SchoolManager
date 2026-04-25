package com.school.service;

import org.springframework.stereotype.Component;

import com.school.model.CalendarEvent;

@Component
public class NoopJoinAutomationClient implements JoinAutomationClient {

    @Override
    public JoinAutomationResult attemptJoin(CalendarEvent event) {
        return new JoinAutomationResult(
                JoinAttemptStatus.FAILED_UNKNOWN,
                "provider_noop",
                "Auto-join provider is set to noop; no browser automation executed.");
    }
}
