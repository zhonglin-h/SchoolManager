package com.school.service;

import java.util.List;

public record GuestSubject(List<String> unmatchedInvitees, List<String> unmatchedParticipants) implements NotificationSubject {
    public Long getId() {
        return null;
    }

    public String getName() {
        return String.join(", ", unmatchedInvitees);
    }
}
