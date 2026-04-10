package com.school.integration;

/**
 * A participant currently in a Google Meet session.
 *
 * @param googleUserId the stable Google account ID (stripped of "users/" prefix), may be null for anonymous users
 * @param displayName  the display name shown in the meeting
 */
public record MeetParticipant(String googleUserId, String displayName) {}
