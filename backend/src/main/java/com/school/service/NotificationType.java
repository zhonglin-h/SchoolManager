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
    NOT_YET_JOINED(
            (e, r) -> roleLabel(r) + " Not Yet Joined: " + r.getName(),
            (e, r) -> r.getName() + " has not yet joined the Meet session for \"" + e.getTitle() + "\".",
            true, true, true
    ),
    ARRIVAL(
            (e, r) -> roleLabel(r) + " Arrived: " + r.getName(),
            (e, r) -> r.getName() + " has joined the Meet session for \"" + e.getTitle() + "\".",
            false, true, false
    ),
    ALL_PRESENT(
            (e, r) -> "All People Present: " + e.getTitle(),
            (e, r) -> "All expected people have joined the Meet session for \"" + e.getTitle() + "\".",
            false, true, false
    ),
    LATE(
            (e, r) -> roleLabel(r) + " Late: " + r.getName(),
            (e, r) -> r.getName() + " joined the Meet session late for \"" + e.getTitle() + "\".",
            false, true, true
    ),
    ABSENT(
            (e, r) -> roleLabel(r) + " Absent: " + r.getName(),
            (e, r) -> r.getName() + " was absent from the Meet session for \"" + e.getTitle() + "\".",
            false, true, true
    ),
    UNMATCHED_GUESTS(
            (e, r) -> "Unknown People in Session: " + e.getTitle(),
            (e, r) -> "",
            true, true, false
    );

    private final BiFunction<CalendarEvent, NotificationSubject, String> subjectFn;
    private final BiFunction<CalendarEvent, NotificationSubject, String> bodyFn;
    final boolean toPrincipalViaEmail;
    final boolean toPrincipalViaTelegram;
    final boolean toParentViaEmail;

    NotificationType(BiFunction<CalendarEvent, NotificationSubject, String> subjectFn,
                     BiFunction<CalendarEvent, NotificationSubject, String> bodyFn,
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

    public boolean shouldSendEmail(NotificationSubject subject) {
        return toPrincipalViaEmail || shouldSendParentEmail(subject);
    }

    public boolean shouldSendParentEmail(NotificationSubject subject) {
        return toParentViaEmail && subject instanceof StudentSubject;
    }

    public String subject(CalendarEvent event, NotificationSubject subject) {
        return subjectFn.apply(event, subject);
    }

    public String body(CalendarEvent event, NotificationSubject subject) {
        return bodyFn.apply(event, subject);
    }

    private static String roleLabel(NotificationSubject subject) {
        if (subject instanceof TeacherSubject) {
            return "Teacher";
        }
        if (subject instanceof StudentSubject) {
            return "Student";
        }
        return "Person";
    }
}