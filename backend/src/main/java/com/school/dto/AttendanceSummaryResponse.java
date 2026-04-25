package com.school.dto;

import com.school.entity.AttendanceStatus;
import com.school.entity.PersonType;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record AttendanceSummaryResponse(
        String calendarEventId,
        String spaceCode,
        String eventTitle,
        LocalDate date,
        LocalTime startTime,
        LocalTime endTime,
        Boolean meetingActive,
        int totalExpected,
        int present,
        int late,
        int absent,
        List<AttendanceEntry> students,
        List<GuestEntry> guests
) {
    public record AttendanceEntry(
            Long personId,
            PersonType personType,
            String name,
            String email,
            AttendanceStatus status,
            boolean registered,
            boolean inMeetNow
    ) {}

    /** A participant currently in the meeting who was not on the calendar invite list. */
    public record GuestEntry(
            String googleUserId,
            String displayName,
            Long personId,
            PersonType personType,
            String registeredName
    ) {}
}
