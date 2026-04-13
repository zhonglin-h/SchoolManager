package com.school.service;

import java.util.function.BiFunction;

import com.school.model.CalendarEvent;

public enum NotificationType {

    //                                                              principalEmail  principalTelegram  parentEmail
    MEETING_NOT_STARTED_15(
            (e, r) -> "Meeting Not Started: " + e.getTitle(),
            (e, r) -> "The Google Meet session for \"" + e.getTitle() + "\" has not been started yet.",
            true, true, false
    ),
    NOT_YET_JOINED_3(
            (e, r) -> "Student Not Yet Joined: " + r.getName(),
            (e, r) -> r.getName() + " has not yet joined the Meet session for \"" + e.getTitle() + "\".",
            true, true, true
    ),
    ARRIVAL(
            (e, r) -> "Student Arrived: " + r.getName(),
            (e, r) -> r.getName() + " has joined the Meet session for \"" + e.getTitle() + "\".",
            false, true, false
    ),
    ALL_PRESENT(
            (e, r) -> "All Students Present: " + e.getTitle(),
            (e, r) -> "All expected students have joined the Meet session for \"" + e.getTitle() + "\".",
            false, true, false
    ),
    LATE(
            (e, r) -> "Student Late: " + r.getName(),
            (e, r) -> r.getName() + " joined the Meet session late for \"" + e.getTitle() + "\".",
            false, true, true
    ),
    ABSENT(
            (e, r) -> "Student Absent: " + r.getName(),
            (e, r) -> r.getName() + " was absent from the Meet session for \"" + e.getTitle() + "\".",
            false, true, true
    ),
    TEACHER_ARRIVED(
            (e, r) -> "Teacher Arrived: " + r.getName(),
            (e, r) -> r.getName() + " has joined the Meet session for \"" + e.getTitle() + "\".",
            false, true, false
    ),
    TEACHER_LATE(
            (e, r) -> "Teacher Late: " + r.getName(),
            (e, r) -> r.getName() + " joined the Meet session late for \"" + e.getTitle() + "\".",
            false, true, false
    ),
    TEACHER_ABSENT(
            (e, r) -> "Teacher Absent: " + r.getName(),
            (e, r) -> r.getName() + " was absent from the Meet session for \"" + e.getTitle() + "\".",
            false, true, false
    );

    private final BiFunction<CalendarEvent, Recipient, String> subjectFn;
    private final BiFunction<CalendarEvent, Recipient, String> bodyFn;
    final boolean toPrincipalViaEmail;
    final boolean toPrincipalViaTelegram;
    final boolean toParentViaEmail;

    NotificationType(BiFunction<CalendarEvent, Recipient, String> subjectFn,
                     BiFunction<CalendarEvent, Recipient, String> bodyFn,
                     boolean toPrincipalViaEmail,
                     boolean toPrincipalViaTelegram,
                     boolean toParentViaEmail) {
        this.subjectFn = subjectFn;
        this.bodyFn = bodyFn;
        this.toPrincipalViaEmail = toPrincipalViaEmail;
        this.toPrincipalViaTelegram = toPrincipalViaTelegram;
        this.toParentViaEmail = toParentViaEmail;
    }

    public boolean shouldSendEmail() {
        return toPrincipalViaEmail || toParentViaEmail;
    }

    public String subject(CalendarEvent event, Recipient recipient) {
        return subjectFn.apply(event, recipient);
    }

    public String body(CalendarEvent event, Recipient recipient) {
        return bodyFn.apply(event, recipient);
    }
}
