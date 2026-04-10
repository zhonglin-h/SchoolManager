package com.school.integration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Stub Meet client for local development without a Google Workspace domain.
 * Enable with app.meet.mock=true in application.properties.
 *
 * Set app.meet.mock.active-emails to a comma-separated list of emails that
 * should be treated as currently active in the meeting.
 * Leave empty to simulate an empty meeting (tests absent/not-joined paths).
 */
@Component
@ConditionalOnProperty(name = "app.meet.mock", havingValue = "true")
public class MockMeetClient implements MeetClient {

    private final List<String> activeEmails;

    public MockMeetClient(
            @Value("${app.meet.mock.active-emails:}") String activeEmailsCsv) {
        if (activeEmailsCsv == null || activeEmailsCsv.isBlank()) {
            this.activeEmails = List.of();
        } else {
            this.activeEmails = Arrays.stream(activeEmailsCsv.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }
    }

    @Override
    public boolean isMeetingActive(String spaceCode) {
        return !activeEmails.isEmpty();
    }

    @Override
    public List<String> getActiveParticipantEmails(String spaceCode) {
        return activeEmails;
    }
}
