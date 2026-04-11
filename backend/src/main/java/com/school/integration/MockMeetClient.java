package com.school.integration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
@ConditionalOnProperty(name = "app.meet.mock", havingValue = "true")
public class MockMeetClient implements MeetClient {

    private final List<MeetParticipant> activeParticipants;

    public MockMeetClient(
            @Value("${app.meet.mock.active-names:}") String activeNamesCsv) {
        java.time.Instant joinTime = java.time.Instant.now();
        if (activeNamesCsv == null || activeNamesCsv.isBlank()) {
            this.activeParticipants = List.of();
        } else {
            this.activeParticipants = Arrays.stream(activeNamesCsv.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(name -> new MeetParticipant(null, name, joinTime))
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

    @Override
    public List<MeetParticipant> getAllParticipants(String spaceCode) {
        return activeParticipants;
    }
}
