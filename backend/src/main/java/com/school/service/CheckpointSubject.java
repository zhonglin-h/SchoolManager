package com.school.service;

import java.util.List;

public record CheckpointSubject(
        String checkLabel,
        List<String> arrivedNames,
        List<String> notArrivedNames,
        List<String> unmatchedInvitees,
        List<String> unmatchedParticipants
) implements NotificationSubject {
    @Override public Long getId() { return null; }
    @Override public String getName() { return checkLabel; }
}
