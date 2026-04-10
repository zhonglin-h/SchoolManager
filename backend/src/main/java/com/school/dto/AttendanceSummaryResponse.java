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
        int totalExpected,
        int present,
        int late,
        int absent,
        List<StudentAttendanceEntry> students
) {
    public record StudentAttendanceEntry(
            Long studentId,
            String name,
            AttendanceStatus status
    ) {}
}
