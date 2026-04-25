package com.school.service;

public record JoinAttemptSubject(String status, String reasonCode, String detailMessage) implements NotificationSubject {
    @Override
    public Long getId() {
        return null;
    }

    @Override
    public String getName() {
        return reasonCode;
    }
}
