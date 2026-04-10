package com.school.service;

import com.school.entity.NotificationChannel;
import com.school.entity.NotificationLog;
import com.school.entity.Student;
import com.school.integration.EmailClient;
import com.school.model.CalendarEvent;
import com.school.repository.NotificationLogRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
public class NotificationService {

    private final NotificationLogRepository notificationLogRepository;
    private final EmailClient emailClient;

    private final String principalEmail;
    private final boolean notificationsEnabled;

    public NotificationService(NotificationLogRepository notificationLogRepository,
                               EmailClient emailClient,
                               @Value("${app.principal.email}") String principalEmail,
                               @Value("${app.notifications.enabled:true}") boolean notificationsEnabled) {
        this.notificationLogRepository = notificationLogRepository;
        this.emailClient = emailClient;
        this.principalEmail = principalEmail;
        this.notificationsEnabled = notificationsEnabled;
    }

    public boolean isNotificationsEnabled() {
        return notificationsEnabled;
    }

    public void notifyMeetingNotStarted(CalendarEvent event, String type) {
        if (!notificationsEnabled) return;
        if (notificationLogRepository.existsByCalendarEventIdAndDateAndTypeAndStudentIsNull(
                event.getId(), LocalDate.now(), type)) {
            return;
        }
        String subject = "Meeting Not Started: " + event.getTitle();
        String body = "The Google Meet session for \"" + event.getTitle() + "\" has not been started yet. Type: " + type;
        emailClient.send(principalEmail, subject, body);
        saveLog(null, event, type, body, NotificationChannel.EMAIL);
    }

    public void notifyNotYetJoined(CalendarEvent event, Student student) {
        if (!notificationsEnabled) return;
        String type = "NOT_YET_JOINED_3";
        if (notificationLogRepository.existsByStudentIdAndCalendarEventIdAndDateAndType(
                student.getId(), event.getId(), LocalDate.now(), type)) {
            return;
        }
        String subject = "Student Not Yet Joined: " + student.getName();
        String body = student.getName() + " has not yet joined the Meet session for \"" + event.getTitle() + "\".";
        emailClient.send(principalEmail, subject, body);
        emailClient.send(student.getParentEmail(), subject, body);
        saveLog(student, event, type, body, NotificationChannel.EMAIL);
    }

    public void notifyArrival(CalendarEvent event, Student student) {
        if (!notificationsEnabled) return;
        String type = "ARRIVAL";
        if (notificationLogRepository.existsByStudentIdAndCalendarEventIdAndDateAndType(
                student.getId(), event.getId(), LocalDate.now(), type)) {
            return;
        }
        String subject = "Student Arrived: " + student.getName();
        String body = student.getName() + " has joined the Meet session for \"" + event.getTitle() + "\".";
        emailClient.send(principalEmail, subject, body);
        saveLog(student, event, type, body, NotificationChannel.EMAIL);
    }

    public void notifyAllPresent(CalendarEvent event) {
        if (!notificationsEnabled) return;
        String type = "ALL_PRESENT";
        if (notificationLogRepository.existsByCalendarEventIdAndDateAndTypeAndStudentIsNull(
                event.getId(), LocalDate.now(), type)) {
            return;
        }
        String subject = "All Students Present: " + event.getTitle();
        String body = "All expected students have joined the Meet session for \"" + event.getTitle() + "\".";
        emailClient.send(principalEmail, subject, body);
        saveLog(null, event, type, body, NotificationChannel.EMAIL);
    }

    public void notifyLate(CalendarEvent event, Student student) {
        if (!notificationsEnabled) return;
        String type = "LATE";
        if (notificationLogRepository.existsByStudentIdAndCalendarEventIdAndDateAndType(
                student.getId(), event.getId(), LocalDate.now(), type)) {
            return;
        }
        String subject = "Student Late: " + student.getName();
        String body = student.getName() + " joined the Meet session late for \"" + event.getTitle() + "\".";
        emailClient.send(student.getParentEmail(), subject, body);
        emailClient.send(principalEmail, subject, body);
        saveLog(student, event, type, body, NotificationChannel.EMAIL);
    }

    public void notifyAbsent(CalendarEvent event, Student student) {
        if (!notificationsEnabled) return;
        String type = "ABSENT";
        if (notificationLogRepository.existsByStudentIdAndCalendarEventIdAndDateAndType(
                student.getId(), event.getId(), LocalDate.now(), type)) {
            return;
        }
        String subject = "Student Absent: " + student.getName();
        String body = student.getName() + " was absent from the Meet session for \"" + event.getTitle() + "\".";
        emailClient.send(student.getParentEmail(), subject, body);
        saveLog(student, event, type, body, NotificationChannel.EMAIL);
    }

    private void saveLog(Student student, CalendarEvent event, String type, String message, NotificationChannel channel) {
        NotificationLog log = NotificationLog.builder()
                .student(student)
                .calendarEventId(event.getId())
                .date(LocalDate.now())
                .type(type)
                .message(message)
                .sentAt(LocalDateTime.now())
                .channel(channel)
                .build();
        notificationLogRepository.save(log);
    }
}
