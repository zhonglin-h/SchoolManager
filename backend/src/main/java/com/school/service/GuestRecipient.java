package com.school.service;

import java.util.List;

public record GuestRecipient(List<String> unmatchedInvitees, List<String> unmatchedParticipants) implements Recipient {
    public Long getId() {
        return null;
    }

    public String getName() {
        return String.join(", ", unmatchedInvitees);
    }
}
