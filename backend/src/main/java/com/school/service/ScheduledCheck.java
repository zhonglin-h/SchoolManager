package com.school.service;

import java.time.Instant;

public record ScheduledCheck(String eventId, String eventTitle, String checkType, Instant scheduledAt) {}
