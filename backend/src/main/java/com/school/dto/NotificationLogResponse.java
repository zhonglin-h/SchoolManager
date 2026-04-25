package com.school.dto;

import com.school.entity.NotificationChannel;
import com.school.entity.NotificationLog;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record NotificationLogResponse(
        Long id,
        Long studentId,
        String calendarEventId,
        LocalDate date,
        String type,
        String message,
        LocalDateTime sentAt,
        NotificationChannel channel,
        boolean success,
        String failureReason,
        String recipient
) {
    public static NotificationLogResponse from(NotificationLog n) {
        return new NotificationLogResponse(
                n.getId(),
                n.getPerson() != null ? n.getPerson().getId() : null,
                n.getCalendarEventId(),
                n.getDate(),
                n.getType(),
                n.getMessage(),
                n.getSentAt(),
                n.getChannel(),
                n.isSuccess(),
                n.getFailureReason(),
                n.getRecipient()
        );
    }
}
