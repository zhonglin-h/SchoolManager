package com.school.service;

import com.school.entity.NotificationChannel;
import com.school.entity.NotificationLog;
import com.school.entity.Student;
import com.school.entity.Teacher;
import com.school.integration.EmailClient;
import com.school.integration.TelegramClient;
import com.school.model.CalendarEvent;
import com.school.repository.NotificationLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class NotificationService {

    private final NotificationLogRepository notificationLogRepository;
    private final EmailClient emailClient;
    private final TelegramClient telegramClient;

    private final String principalEmail;
    private final boolean notificationsEnabled;
    private final String telegramChatId;

    public NotificationService(NotificationLogRepository notificationLogRepository,
                               EmailClient emailClient,
                               TelegramClient telegramClient,
                               @Value("${app.principal.email}") String principalEmail,
                               @Value("${app.notifications.enabled:true}") boolean notificationsEnabled,
                               @Value("${telegram.chat-id}") String telegramChatId) {
        this.notificationLogRepository = notificationLogRepository;
        this.emailClient = emailClient;
        this.telegramClient = telegramClient;
        this.principalEmail = principalEmail;
        this.notificationsEnabled = notificationsEnabled;
        this.telegramChatId = telegramChatId;
    }

    public boolean isNotificationsEnabled() {
        return notificationsEnabled;
    }

    @Transactional
    public void clearTodayLogsForEvent(String calendarEventId) {
        notificationLogRepository.deleteByCalendarEventIdAndDate(calendarEventId, LocalDate.now());
        log.info("Cleared today's notification logs for rescheduled event {}", calendarEventId);
    }

    @Transactional
    public void notify(NotificationType type, CalendarEvent event, @Nullable Recipient recipient) {
        if (!notificationsEnabled) return;

        Student student = recipient instanceof StudentRecipient sr ? sr.student() : null;
        Teacher teacher = recipient instanceof TeacherRecipient tr ? tr.teacher() : null;

        // --- Email path ---
        if (type.shouldSendEmail()) {
            boolean emailAlreadySent = shouldDedup(type)
                    && dedupCheck(recipient, event, type, NotificationChannel.EMAIL);

            if (!emailAlreadySent) {
                String subject = type.subject(event, recipient);
                String body = resolveBody(type, event, recipient);
                String failureReason = null;
                List<String> recipients = new ArrayList<>();

                if (type.toPrincipalViaEmail) {
                    recipients.add(principalEmail);
                    try {
                        emailClient.send(principalEmail, subject, body);
                    } catch (Exception e) {
                        failureReason = e.getMessage();
                        log.error("Failed to send {} email to principal: {}", type.name(), e.getMessage());
                    }
                }
                if (type.toParentViaEmail && student != null
                        && student.getParentEmail() != null && !student.getParentEmail().isBlank()) {
                    recipients.add(student.getParentEmail());
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
                        .teacher(teacher)
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
                notificationLogRepository.save(emailEntry);
            }
        }

        // --- Telegram path ---
        if (type.toPrincipalViaTelegram) {
            boolean telegramAlreadySent = shouldDedup(type)
                    && dedupCheck(recipient, event, type, NotificationChannel.TELEGRAM);

            if (!telegramAlreadySent) {
                String body = resolveBody(type, event, recipient);
                String failureReason = null;

                try {
                    telegramClient.send(body);
                } catch (Exception e) {
                    failureReason = e.getMessage();
                    log.error("Failed to send {} Telegram notification: {}", type.name(), e.getMessage());
                }

                NotificationLog telegramEntry = NotificationLog.builder()
                        .student(student)
                        .teacher(teacher)
                        .calendarEventId(event.getId())
                        .date(LocalDate.now())
                        .type(type.name())
                        .message(body)
                        .sentAt(LocalDateTime.now())
                        .channel(NotificationChannel.TELEGRAM)
                        .recipient(telegramChatId)
                        .success(failureReason == null)
                        .failureReason(failureReason)
                        .build();
                notificationLogRepository.save(telegramEntry);
            }
        }
    }

    private boolean shouldDedup(NotificationType type) {
        return type != NotificationType.UNMATCHED_GUESTS;
    }

    private String resolveBody(NotificationType type, CalendarEvent event, @Nullable Recipient recipient) {
        if (type == NotificationType.UNMATCHED_GUESTS && recipient instanceof GuestRecipient guestRecipient) {
            List<String> sections = new ArrayList<>();
            sections.add("Meet: \"" + event.getTitle() + "\" (space: " + event.getSpaceCode() + ")");
            if (!guestRecipient.unmatchedInvitees().isEmpty()) {
                sections.add("Invited but not found in system: "
                        + String.join(", ", guestRecipient.unmatchedInvitees()));
            }
            if (!guestRecipient.unmatchedParticipants().isEmpty()) {
                sections.add("In room but not found in system: "
                        + String.join(", ", guestRecipient.unmatchedParticipants()));
            }
            return String.join("\n", sections);
        }
        return type.body(event, recipient);
    }

    private boolean dedupCheck(@Nullable Recipient recipient, CalendarEvent event,
                               NotificationType type, NotificationChannel channel) {
        if (recipient instanceof StudentRecipient sr) {
            return notificationLogRepository
                    .existsByStudentIdAndCalendarEventIdAndDateAndTypeAndChannelAndSuccessTrue(
                            sr.getId(), event.getId(), LocalDate.now(), type.name(), channel);
        } else if (recipient instanceof TeacherRecipient tr) {
            return notificationLogRepository
                    .existsByTeacherIdAndCalendarEventIdAndDateAndTypeAndChannelAndSuccessTrue(
                            tr.getId(), event.getId(), LocalDate.now(), type.name(), channel);
        } else {
            return notificationLogRepository
                    .existsByCalendarEventIdAndDateAndTypeAndChannelAndStudentIsNullAndSuccessTrue(
                            event.getId(), LocalDate.now(), type.name(), channel);
        }
    }
}
