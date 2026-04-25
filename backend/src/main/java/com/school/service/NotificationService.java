package com.school.service;

import com.school.entity.NotificationChannel;
import com.school.entity.NotificationLog;
import com.school.entity.Person;
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
    private final boolean emailNotificationsEnabled;
    private final String telegramChatId;

    public NotificationService(NotificationLogRepository notificationLogRepository,
                               EmailClient emailClient,
                               TelegramClient telegramClient,
                               @Value("${app.principal.email}") String principalEmail,
                               @Value("${app.notifications.enabled:true}") boolean notificationsEnabled,
                               @Value("${app.notifications.email.enabled:true}") boolean emailNotificationsEnabled,
                               @Value("${telegram.chat-id}") String telegramChatId) {
        this.notificationLogRepository = notificationLogRepository;
        this.emailClient = emailClient;
        this.telegramClient = telegramClient;
        this.principalEmail = principalEmail;
        this.notificationsEnabled = notificationsEnabled;
        this.emailNotificationsEnabled = emailNotificationsEnabled;
        this.telegramChatId = telegramChatId;
    }

    public boolean isNotificationsEnabled() {
        return notificationsEnabled;
    }

    public boolean isEmailNotificationsEnabled() {
        return emailNotificationsEnabled;
    }

    @Transactional
    public void clearTodayLogsForEvent(String calendarEventId) {
        notificationLogRepository.deleteByCalendarEventIdAndDate(calendarEventId, LocalDate.now());
        log.info("Cleared today's notification logs for rescheduled event {}", calendarEventId);
    }

    @Transactional
    public void notify(NotificationType type, CalendarEvent event, @Nullable NotificationSubject subject) {
        if (!notificationsEnabled) return;

        Person person = subject instanceof StudentSubject ss ? ss.student()
                : subject instanceof TeacherSubject ts ? ts.teacher()
                : null;

        // --- Email path ---
        if (emailNotificationsEnabled && type.shouldSendEmail(subject)) {
            boolean emailAlreadySent = shouldDedup(type)
                    && dedupCheck(subject, event, type, NotificationChannel.EMAIL);

            if (!emailAlreadySent) {
                String emailSubject = type.subject(event, subject);
                String body = resolveBody(type, event, subject);
                String failureReason = null;
                List<String> recipients = new ArrayList<>();

                if (type.toPrincipalViaEmail) {
                    recipients.add(principalEmail);
                    try {
                        emailClient.send(principalEmail, emailSubject, body);
                    } catch (Exception e) {
                        failureReason = e.getMessage();
                        log.error("Failed to send {} email to principal: {}", type.name(), e.getMessage());
                    }
                }
                if (type.shouldSendParentEmail(subject) && person != null
                        && person.getParentEmail() != null && !person.getParentEmail().isBlank()) {
                    recipients.add(person.getParentEmail());
                    try {
                        emailClient.send(person.getParentEmail(), emailSubject, body);
                    } catch (Exception e) {
                        failureReason = e.getMessage();
                        log.error("Failed to send {} email to parent of {}: {}", type.name(),
                                person.getName(), e.getMessage());
                    }
                }

                NotificationLog emailEntry = NotificationLog.builder()
                        .person(person)
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
                    && dedupCheck(subject, event, type, NotificationChannel.TELEGRAM);

            if (!telegramAlreadySent) {
                String body = resolveBody(type, event, subject);
                String failureReason = null;

                try {
                    telegramClient.send(body);
                } catch (Exception e) {
                    failureReason = e.getMessage();
                    log.error("Failed to send {} Telegram notification: {}", type.name(), e.getMessage());
                }

                NotificationLog telegramEntry = NotificationLog.builder()
                        .person(person)
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

    private String resolveBody(NotificationType type, CalendarEvent event, @Nullable NotificationSubject subject) {
        if (type == NotificationType.UNMATCHED_GUESTS && subject instanceof GuestSubject guestSubject) {
            List<String> sections = new ArrayList<>();
            sections.add("Meet: \"" + event.getTitle() + "\" (space: " + event.getSpaceCode() + ")");
            if (!guestSubject.unmatchedInvitees().isEmpty()) {
                sections.add("Invited but not found in system: "
                        + String.join(", ", guestSubject.unmatchedInvitees()));
            }
            if (!guestSubject.unmatchedParticipants().isEmpty()) {
                sections.add("In room but not found in system: "
                        + String.join(", ", guestSubject.unmatchedParticipants()));
            }
            return String.join("\n", sections);
        }
        return type.body(event, subject);
    }

    private boolean dedupCheck(@Nullable NotificationSubject subject, CalendarEvent event,
                               NotificationType type, NotificationChannel channel) {
        if (subject instanceof StudentSubject ss) {
            return notificationLogRepository
                    .existsByPersonIdAndCalendarEventIdAndDateAndTypeAndChannelAndSuccessTrue(
                            ss.getId(), event.getId(), LocalDate.now(), type.name(), channel);
        } else if (subject instanceof TeacherSubject ts) {
            return notificationLogRepository
                    .existsByPersonIdAndCalendarEventIdAndDateAndTypeAndChannelAndSuccessTrue(
                            ts.getId(), event.getId(), LocalDate.now(), type.name(), channel);
        } else {
            return notificationLogRepository
                    .existsByCalendarEventIdAndDateAndTypeAndChannelAndPersonIsNullAndSuccessTrue(
                            event.getId(), LocalDate.now(), type.name(), channel);
        }
    }
}
