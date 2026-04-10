package com.school.integration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Stub Meet client for local development.
 * Enable with app.meet.mock=true in application.properties.
 *
 * Set app.meet.mock.active-names to a comma-separated list of display names
 * that should be treated as currently active in the meeting.
 * Leave empty to simulate an empty meeting.
 */
@Component
@ConditionalOnProperty(name = "app.meet.mock", havingValue = "true")
public class MockMeetClient implements MeetClient {

    private final List<MeetParticipant> activeParticipants;

    public MockMeetClient(
            @Value("${app.meet.mock.active-names:}") String activeNamesCsv) {
        if (activeNamesCsv == null || activeNamesCsv.isBlank()) {
            this.activeParticipants = List.of();
        } else {
            this.activeParticipants = Arrays.stream(activeNamesCsv.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(name -> new MeetParticipant(null, name))
                    .toList();
        }
    }

    @Override
    public boolean isMeetingActive(String spaceCode) {
        return !activeParticipants.isEmpty();
    }

    @Override
    public List<MeetParticipant> getActiveParticipants(String spaceCode) {
        return activeParticipants;
    }
}
