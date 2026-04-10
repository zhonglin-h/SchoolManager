package com.school.controller;

import com.school.service.MeetAttendanceMonitor;
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

    public CalendarController(MeetAttendanceMonitor meetAttendanceMonitor) {
        this.meetAttendanceMonitor = meetAttendanceMonitor;
    }

    @PostMapping("/sync")
    public ResponseEntity<Void> sync() {
        meetAttendanceMonitor.scheduleEventsForToday();
        return ResponseEntity.ok().build();
    }

    @GetMapping("/scheduled-checks")
    public ResponseEntity<List<MeetAttendanceMonitor.ScheduledCheck>> getScheduledChecks() {
        return ResponseEntity.ok(meetAttendanceMonitor.getUpcomingChecks());
    }
}
