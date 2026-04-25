package com.school.service;

/**
 * Represents the subject of a notification — the person the notification is about.
 * The actual delivery targets (email addresses, Telegram chat ID) are determined
 * by the {@link NotificationType} flags and stored separately in the notification log.
 */
public sealed interface NotificationSubject permits PersonSubject, GuestSubject, JoinAttemptSubject {
    Long getId();
    String getName();
}
