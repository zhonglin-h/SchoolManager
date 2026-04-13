package com.school.service;

import com.school.entity.Student;
import com.school.model.CalendarEvent;

import java.util.function.BiFunction;

public enum NotificationType {

    //                                                         principalEmail  principalTelegram  parentEmail
    MEETING_NOT_STARTED_15(
            (e, s) -> "Meeting Not Started: " + e.getTitle(),
            (e, s) -> "The Google Meet session for \"" + e.getTitle() + "\" has not been started yet.",
            true, false, false
    ),
    NOT_YET_JOINED_3(
            (e, s) -> "Student Not Yet Joined: " + s.getName(),
            (e, s) -> s.getName() + " has not yet joined the Meet session for \"" + e.getTitle() + "\".",
            true, false, true
    ),
    ARRIVAL(
            (e, s) -> "Student Arrived: " + s.getName(),
            (e, s) -> s.getName() + " has joined the Meet session for \"" + e.getTitle() + "\".",
            false, true, false
    ),
    ALL_PRESENT(
            (e, s) -> "All Students Present: " + e.getTitle(),
            (e, s) -> "All expected students have joined the Meet session for \"" + e.getTitle() + "\".",
            false, true, false
    ),
    LATE(
            (e, s) -> "Student Late: " + s.getName(),
            (e, s) -> s.getName() + " joined the Meet session late for \"" + e.getTitle() + "\".",
            false, true, true
    ),
    ABSENT(
            (e, s) -> "Student Absent: " + s.getName(),
            (e, s) -> s.getName() + " was absent from the Meet session for \"" + e.getTitle() + "\".",
            false, true, true
    );

    private final BiFunction<CalendarEvent, Student, String> subjectFn;
    private final BiFunction<CalendarEvent, Student, String> bodyFn;
    final boolean toPrincipalViaEmail;
    final boolean toPrincipalViaTelegram;
    final boolean toParentViaEmail;

    NotificationType(BiFunction<CalendarEvent, Student, String> subjectFn,
                     BiFunction<CalendarEvent, Student, String> bodyFn,
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

    public String subject(CalendarEvent event, Student student) {
        return subjectFn.apply(event, student);
    }

    public String body(CalendarEvent event, Student student) {
        return bodyFn.apply(event, student);
    }
}
