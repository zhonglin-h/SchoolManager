package com.school.service;

public record JoinAutomationResult(
        JoinAttemptStatus status,
        String reasonCode,
        String detailMessage
) {
}
