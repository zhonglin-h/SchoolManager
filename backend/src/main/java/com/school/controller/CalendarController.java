package com.school.controller;

import com.school.service.MeetAttendanceMonitor;
import com.school.service.MeetJoinService;
import com.school.service.CalendarSyncService;
import com.school.entity.JoinAttemptLog;
import com.school.model.CalendarEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/calendar")
public class CalendarController {

    private final MeetAttendanceMonitor meetAttendanceMonitor;
    private final MeetJoinService meetJoinService;
    private final CalendarSyncService calendarSyncService;

    @Value("${app.dashboard.upcoming-checks-limit}")
    private int upcomingChecksLimit;

    public CalendarController(MeetAttendanceMonitor meetAttendanceMonitor,
                              MeetJoinService meetJoinService,
                              CalendarSyncService calendarSyncService) {
        this.meetAttendanceMonitor = meetAttendanceMonitor;
        this.meetJoinService = meetJoinService;
        this.calendarSyncService = calendarSyncService;
    }

    public record ScheduledChecksResponse(
            List<MeetAttendanceMonitor.ScheduledCheck> checks,
            int total,
            int limit
    ) {}

    public record JoinAttemptResponse(
            Long id,
            String calendarEventId,
            LocalDateTime scheduledStart,
            LocalDateTime attemptedAt,
            String triggerType,
            String status,
            String reasonCode,
            String detailMessage
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

    @GetMapping("/join-attempts/today")
    public ResponseEntity<List<JoinAttemptResponse>> getTodayJoinAttempts() {
        List<JoinAttemptResponse> responses = meetJoinService.getTodayAttempts().stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @PostMapping("/{eventId}/join")
    public ResponseEntity<JoinAttemptResponse> triggerJoin(@PathVariable String eventId) {
        CalendarEvent event = findTodayEvent(eventId);
        JoinAttemptLog log = meetJoinService.attemptManualJoin(event);
        return ResponseEntity.ok(toResponse(log));
    }

    private CalendarEvent findTodayEvent(String eventId) {
        try {
            return calendarSyncService.getTodaysEvents().stream()
                    .filter(event -> eventId.equals(event.getId()))
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found for today: " + eventId));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to load today's events", e);
        }
    }

    private JoinAttemptResponse toResponse(JoinAttemptLog log) {
        return new JoinAttemptResponse(
                log.getId(),
                log.getCalendarEventId(),
                log.getScheduledStart(),
                log.getAttemptedAt(),
                log.getTriggerType(),
                log.getStatus(),
                log.getReasonCode(),
                log.getDetailMessage());
    }
}
