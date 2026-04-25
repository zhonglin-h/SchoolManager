package com.school.dto;

import com.school.entity.AttendanceStatus;

import java.time.LocalDate;

public record StudentAttendanceRecord(
        String calendarEventId,
        LocalDate date,
        AttendanceStatus status
) {}
