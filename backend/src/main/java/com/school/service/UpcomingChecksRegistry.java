package com.school.service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/**
 * Shared registry of upcoming and active scheduled monitoring checks.
 * Injected by both {@link MeetAttendanceMonitor} (adds one-time and polling entries)
 * and {@link MeetSessionHandler} (removes the polling entry when the loop stops).
 */
@Component
public class UpcomingChecksRegistry {

    private final CopyOnWriteArrayList<ScheduledCheck> checks = new CopyOnWriteArrayList<>();

    public void add(ScheduledCheck check) {
        checks.add(check);
    }

    /** Removes the entry for the given event and check type. */
    public void remove(String eventId, String checkType) {
        checks.removeIf(c -> c.eventId().equals(eventId) && c.checkType().equals(checkType));
    }

    /** Removes the SESSION_POLLING entry for the given event, if present. */
    public void removePollingEntry(String eventId) {
        checks.removeIf(c -> c.eventId().equals(eventId) && "SESSION_POLLING".equals(c.checkType()));
    }

    /** Clears all entries. */
    public void clear() {
        checks.clear();
    }

    /** Returns all entries whose scheduled time is still in the future, sorted ascending. */
    public List<ScheduledCheck> getUpcoming() {
        Instant now = Instant.now();
        return checks.stream()
                .filter(c -> c.scheduledAt().isAfter(now))
                .sorted(Comparator.comparing(ScheduledCheck::scheduledAt))
                .collect(Collectors.toList());
    }
}
