package com.school.controller;

import com.school.service.MeetAttendanceMonitor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/calendar")
public class CalendarController {

    private final MeetAttendanceMonitor meetAttendanceMonitor;

    @Value("${app.dashboard.upcoming-checks-limit}")
    private int upcomingChecksLimit;

    public CalendarController(MeetAttendanceMonitor meetAttendanceMonitor) {
        this.meetAttendanceMonitor = meetAttendanceMonitor;
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
}
