package com.school.integration;

/**
 * A participant in a Google Meet session.
 *
 * @param googleUserId      the stable Google account ID (stripped of "users/" prefix), null for anonymous users
 * @param displayName       the display name shown in the meeting
 * @param earliestStartTime when this participant first joined, from the Meet API (UTC); null if unavailable
 */
public record MeetParticipant(String googleUserId, String displayName, java.time.Instant earliestStartTime) {}
