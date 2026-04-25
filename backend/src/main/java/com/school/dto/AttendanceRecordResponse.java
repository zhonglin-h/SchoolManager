package com.school.dto;

import com.school.entity.AttendanceStatus;
import com.school.entity.PersonType;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record AttendanceRecordResponse(
        Long id,
        Long personId,
        PersonType personType,
        String personName,
        String calendarEventId,
        String eventTitle,
        LocalDate date,
        AttendanceStatus status,
        LocalDateTime updatedAt
) {}
