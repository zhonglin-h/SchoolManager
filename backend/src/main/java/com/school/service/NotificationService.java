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
                ? notificationLogRepository.existsByStudentIdAndCalendarEventIdAndDateAndTypeAndSuccessTrue(
                        student.getId(), event.getId(), LocalDate.now(), type.name())
                : notificationLogRepository.existsByCalendarEventIdAndDateAndTypeAndStudentIsNullAndSuccessTrue(
                        event.getId(), LocalDate.now(), type.name());
        if (alreadySent) return;

        String subject = type.subject(event, student);
        String body = type.body(event, student);
        String failureReason = null;

        java.util.List<String> recipients = new java.util.ArrayList<>();

        if (type.toPrincipal) {
            recipients.add(principalEmail);
            try {
                emailClient.send(principalEmail, subject, body);
            } catch (Exception e) {
                failureReason = e.getMessage();
                log.error("Failed to send {} notification to principal: {}", type.name(), e.getMessage());
            }
        }
        if (type.toParent && student != null
                && student.getParentEmail() != null && !student.getParentEmail().isBlank()) {
            recipients.add(student.getParentEmail());
            try {
                emailClient.send(student.getParentEmail(), subject, body);
            } catch (Exception e) {
                failureReason = e.getMessage();
                log.error("Failed to send {} notification to parent of {}: {}", type.name(),
                        student.getName(), e.getMessage());
            }
        }

        NotificationLog entry = NotificationLog.builder()
                .student(student)
                .calendarEventId(event.getId())
                .date(LocalDate.now())
                .type(type.name())
                .message(body)
                .sentAt(LocalDateTime.now())
                .channel(NotificationChannel.EMAIL)
                .recipient(String.join(", ", recipients))
                .success(failureReason == null)
                .failureReason(failureReason)
                .build();
        notificationLogRepository.save(entry);
    }
}
