package com.school.service;

import com.school.entity.NotificationChannel;
import com.school.entity.NotificationLog;
import com.school.entity.Student;
import com.school.integration.EmailClient;
import com.school.integration.TelegramClient;
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
    private final TelegramClient telegramClient;

    private final String principalEmail;
    private final boolean notificationsEnabled;

    public NotificationService(NotificationLogRepository notificationLogRepository,
                               EmailClient emailClient,
                               TelegramClient telegramClient,
                               @Value("${app.principal.email}") String principalEmail,
                               @Value("${app.notifications.enabled:true}") boolean notificationsEnabled) {
        this.notificationLogRepository = notificationLogRepository;
        this.emailClient = emailClient;
        this.telegramClient = telegramClient;
        this.principalEmail = principalEmail;
        this.notificationsEnabled = notificationsEnabled;
    }

    public boolean isNotificationsEnabled() {
        return notificationsEnabled;
    }

    public void notify(NotificationType type, CalendarEvent event, Student student) {
        if (!notificationsEnabled) return;

        // --- Email path ---
        if (type.shouldSendEmail()) {
            boolean emailAlreadySent = student != null
                    ? notificationLogRepository.existsByStudentIdAndCalendarEventIdAndDateAndTypeAndChannelAndSuccessTrue(
                            student.getId(), event.getId(), LocalDate.now(), type.name(), NotificationChannel.EMAIL)
                    : notificationLogRepository.existsByCalendarEventIdAndDateAndTypeAndChannelAndStudentIsNullAndSuccessTrue(
                            event.getId(), LocalDate.now(), type.name(), NotificationChannel.EMAIL);

            if (!emailAlreadySent) {
                String subject = type.subject(event, student);
                String body = type.body(event, student);
                String failureReason = null;

                if (type.toPrincipal && !type.principalViaTelegram) {
                    try {
                        emailClient.send(principalEmail, subject, body);
                    } catch (Exception e) {
                        failureReason = e.getMessage();
                        log.error("Failed to send {} email to principal: {}", type.name(), e.getMessage());
                    }
                }
                if (type.toParent && student != null
                        && student.getParentEmail() != null && !student.getParentEmail().isBlank()) {
                    try {
                        emailClient.send(student.getParentEmail(), subject, body);
                    } catch (Exception e) {
                        failureReason = e.getMessage();
                        log.error("Failed to send {} email to parent of {}: {}", type.name(),
                                student.getName(), e.getMessage());
                    }
                }

                NotificationLog emailEntry = NotificationLog.builder()
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
                notificationLogRepository.save(emailEntry);
            }
        }

        // --- Telegram path ---
        if (type.toPrincipal && type.principalViaTelegram) {
            boolean telegramAlreadySent = student != null
                    ? notificationLogRepository.existsByStudentIdAndCalendarEventIdAndDateAndTypeAndChannelAndSuccessTrue(
                            student.getId(), event.getId(), LocalDate.now(), type.name(), NotificationChannel.TELEGRAM)
                    : notificationLogRepository.existsByCalendarEventIdAndDateAndTypeAndChannelAndStudentIsNullAndSuccessTrue(
                            event.getId(), LocalDate.now(), type.name(), NotificationChannel.TELEGRAM);

            if (!telegramAlreadySent) {
                String body = type.body(event, student);
                String failureReason = null;

                try {
                    telegramClient.send(body);
                } catch (Exception e) {
                    failureReason = e.getMessage();
                    log.error("Failed to send {} Telegram notification: {}", type.name(), e.getMessage());
                }

                NotificationLog telegramEntry = NotificationLog.builder()
                        .student(student)
                        .calendarEventId(event.getId())
                        .date(LocalDate.now())
                        .type(type.name())
                        .message(body)
                        .sentAt(LocalDateTime.now())
                        .channel(NotificationChannel.TELEGRAM)
                        .success(failureReason == null)
                        .failureReason(failureReason)
                        .build();
                notificationLogRepository.save(telegramEntry);
            }
        }
    }
}
