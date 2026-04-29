package com.school.controller;

import com.school.entity.JoinAttemptLog;
import com.school.model.CalendarEvent;
import com.school.service.CalendarSyncService;
import com.school.service.JoinAttemptService;
import com.school.service.MeetAttendanceMonitor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/calendar")
public class CalendarController {

    private final MeetAttendanceMonitor meetAttendanceMonitor;
    private final CalendarSyncService calendarSyncService;
    private final JoinAttemptService joinAttemptService;

    @Value("${app.dashboard.upcoming-checks-limit}")
    private int upcomingChecksLimit;

    public CalendarController(MeetAttendanceMonitor meetAttendanceMonitor,
                              CalendarSyncService calendarSyncService,
                              JoinAttemptService joinAttemptService) {
        this.meetAttendanceMonitor = meetAttendanceMonitor;
        this.calendarSyncService = calendarSyncService;
        this.joinAttemptService = joinAttemptService;
    }

    public record ScheduledChecksResponse(
            List<MeetAttendanceMonitor.ScheduledCheck> checks,
            int total,
            int limit
    ) {}

    @PostMapping("/sync")
    public ResponseEntity<Void> sync() {
        meetAttendanceMonitor.scheduleEventsForToday();
        return ResponseEntity.ok().build();
    }

    @GetMapping("/scheduled-checks")
    public ResponseEntity<ScheduledChecksResponse> getScheduledChecks() {
        List<MeetAttendanceMonitor.ScheduledCheck> all = meetAttendanceMonitor.getUpcomingChecks();
        List<MeetAttendanceMonitor.ScheduledCheck> limited = all.stream()
                .limit(upcomingChecksLimit)
                .toList();
        return ResponseEntity.ok(new ScheduledChecksResponse(limited, all.size(), upcomingChecksLimit));
    }

    /**
     * Returns all join attempt log entries for today, newest first.
     */
    @GetMapping("/join-attempts/today")
    public ResponseEntity<List<JoinAttemptLog>> getTodayJoinAttempts() {
        return ResponseEntity.ok(joinAttemptService.getTodayAttempts());
    }

    /**
     * Manually triggers an auto-join attempt for the given calendar event (today's schedule only).
     * Uses trigger type {@code "MANUAL"} and always records a timestamped join attempt log entry.
     *
     * @param eventId the Google Calendar event ID
     * @return 200 with the {@link JoinAttemptLog}, or 404 if the event is not on today's schedule
     */
    @PostMapping("/{eventId}/join")
    public ResponseEntity<JoinAttemptLog> triggerManualJoin(@PathVariable String eventId) {
        List<CalendarEvent> todayEvents;
        try {
            todayEvents = calendarSyncService.getTodaysEvents();
        } catch (Exception e) {
            log.error("Failed to fetch today's events for manual join trigger on '{}': {}", eventId, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
        CalendarEvent event = todayEvents.stream()
                .filter(e -> e.getId().equals(eventId))
                .findFirst()
                .orElse(null);
        if (event == null) {
            return ResponseEntity.notFound().build();
        }
        JoinAttemptLog log = joinAttemptService.attemptJoin(event, "MANUAL");
        return ResponseEntity.ok(log);
    }
}
