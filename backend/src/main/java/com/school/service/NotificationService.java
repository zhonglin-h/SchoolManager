package com.school.service;

import com.school.entity.NotificationChannel;
import com.school.entity.NotificationLog;
import com.school.entity.Student;
import com.school.integration.EmailClient;
import com.school.model.CalendarEvent;
import com.school.repository.NotificationLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Slf4j
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

    public void notify(NotificationType type, CalendarEvent event, Student student) {
        if (!notificationsEnabled) return;

        boolean alreadySent = student != null
                ? notificationLogRepository.existsByStudentIdAndCalendarEventIdAndDateAndType(
                        student.getId(), event.getId(), LocalDate.now(), type.name())
                : notificationLogRepository.existsByCalendarEventIdAndDateAndTypeAndStudentIsNull(
                        event.getId(), LocalDate.now(), type.name());
        if (alreadySent) return;

        String subject = type.subject(event, student);
        String body = type.body(event, student);
        String failureReason = null;

        try {
            if (type.toPrincipal) {
                emailClient.send(principalEmail, subject, body);
            }
            if (type.toParent && student != null
                    && student.getParentEmail() != null && !student.getParentEmail().isBlank()) {
                emailClient.send(student.getParentEmail(), subject, body);
            }
        } catch (Exception e) {
            failureReason = e.getMessage();
            log.error("Failed to send {} notification{}: {}", type.name(),
                    student != null ? " for student " + student.getName() : "", e.getMessage());
        }

        NotificationLog entry = NotificationLog.builder()
                .student(student)
                .calendarEventId(event.getId())
                .date(LocalDate.now())
                .type(type.name())
                .message(body)
                .sentAt(LocalDateTime.now())
                .channel(NotificationChannel.EMAIL)
                .success(failureReason == null)
                .failureReason(failureReason)
                .build();
        notificationLogRepository.save(entry);
    }
}
