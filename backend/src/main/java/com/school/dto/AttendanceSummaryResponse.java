package com.school.dto;

import com.school.entity.AttendanceStatus;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record AttendanceSummaryResponse(
        String calendarEventId,
        String eventTitle,
        LocalDate date,
        LocalTime startTime,
        LocalTime endTime,
        Boolean meetingActive,
        int totalExpected,
        int present,
        int late,
        int absent,
        List<AttendanceEntry> students
) {
    public record AttendanceEntry(
            Long personId,
            String personType,
            String name,
            String email,
            AttendanceStatus status,
            boolean registered
    ) {}
}
