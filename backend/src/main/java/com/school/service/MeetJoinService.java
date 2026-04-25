package com.school.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;

import com.school.config.AutoJoinProperties;
import com.school.entity.JoinAttemptLog;
import com.school.integration.MeetClient;
import com.school.model.CalendarEvent;
import com.school.repository.JoinAttemptLogRepository;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class MeetJoinService {

    private static final String SCHEDULED_TRIGGER = "SCHEDULED";
    private static final String MANUAL_TRIGGER = "MANUAL";

    private final AutoJoinProperties autoJoinProperties;
    private final JoinAttemptLogRepository joinAttemptLogRepository;
    private final NotificationService notificationService;
    private final MeetClient meetClient;
    private final JoinAutomationClient noopJoinAutomationClient;
    private final PlaywrightJoinAutomationClient playwrightJoinAutomationClient;

    public MeetJoinService(AutoJoinProperties autoJoinProperties,
                           JoinAttemptLogRepository joinAttemptLogRepository,
                           NotificationService notificationService,
                           MeetClient meetClient,
                           NoopJoinAutomationClient noopJoinAutomationClient,
                           PlaywrightJoinAutomationClient playwrightJoinAutomationClient) {
        this.autoJoinProperties = autoJoinProperties;
        this.joinAttemptLogRepository = joinAttemptLogRepository;
        this.notificationService = notificationService;
        this.meetClient = meetClient;
        this.noopJoinAutomationClient = noopJoinAutomationClient;
        this.playwrightJoinAutomationClient = playwrightJoinAutomationClient;
    }

    public JoinAttemptLog attemptScheduledJoin(CalendarEvent event) {
        return attemptJoin(event, SCHEDULED_TRIGGER, false);
    }

    public JoinAttemptLog attemptManualJoin(CalendarEvent event) {
        return attemptJoin(event, MANUAL_TRIGGER, true);
    }

    public List<JoinAttemptLog> getTodayAttempts() {
        return joinAttemptLogRepository.findByDateOrderByAttemptedAtDesc(LocalDate.now());
    }

    private JoinAttemptLog attemptJoin(CalendarEvent event, String triggerType, boolean bypassIdempotency) {
        if (event == null) {
            throw new IllegalArgumentException("event cannot be null");
        }
        if (event.getId() == null || event.getId().isBlank()) {
            throw new IllegalArgumentException("calendar event id is required");
        }
        if (!autoJoinProperties.isEnabled()) {
            JoinAttemptLog disabledLog = saveResult(event, triggerType, new JoinAutomationResult(
                    JoinAttemptStatus.FAILED_PERMISSION,
                    "autojoin_disabled",
                    "Auto-join is disabled via configuration."));
            maybeNotify(event, disabledLog);
            return disabledLog;
        }

        LocalDate today = LocalDate.now();
        if (!bypassIdempotency && joinAttemptLogRepository.existsByCalendarEventIdAndDateAndTriggerType(event.getId(), today, triggerType)) {
            return joinAttemptLogRepository.findTopByCalendarEventIdAndDateAndTriggerTypeOrderByAttemptedAtDesc(
                    event.getId(), today, triggerType).orElse(null);
        }

        if (autoJoinProperties.isSkipIfMeetingActive()
                && event.getSpaceCode() != null
                && !event.getSpaceCode().isBlank()) {
            try {
                if (meetClient.isMeetingActive(event.getSpaceCode())) {
                    JoinAttemptLog skipped = saveResult(event, triggerType, new JoinAutomationResult(
                            JoinAttemptStatus.JOINED,
                            "meeting_already_active",
                            "Meeting is already active; skipping auto-join launch."));
                    maybeNotify(event, skipped);
                    return skipped;
                }
            } catch (Exception ignored) {
                log.debug("Failed to probe meeting active status before auto-join for {}: {}",
                        event.getId(), ignored.getMessage());
                // continue to auto-join attempt on status probe failure
            }
        }

        JoinAutomationResult result = executeWithRetry(selectClient(), event);
        JoinAttemptLog saved = saveResult(event, triggerType, result);
        maybeNotify(event, saved);
        return saved;
    }

    private JoinAutomationClient selectClient() {
        if ("playwright".equalsIgnoreCase(autoJoinProperties.getProvider())) {
            return playwrightJoinAutomationClient;
        }
        return noopJoinAutomationClient;
    }

    private JoinAutomationResult executeWithRetry(JoinAutomationClient client, CalendarEvent event) {
        JoinAutomationResult last = null;
        int attempts = Math.max(1, autoJoinProperties.getRetryMaxAttempts());
        for (int i = 1; i <= attempts; i++) {
            last = client.attemptJoin(event);
            if (last.status() == JoinAttemptStatus.JOINED) {
                return last;
            }

            if (i < attempts) {
                try {
                    Thread.sleep(Math.max(0, autoJoinProperties.getRetryBackoffMs()));
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return new JoinAutomationResult(JoinAttemptStatus.FAILED_UNKNOWN, "interrupted", "Retry loop interrupted.");
                }
            }
        }
        return last;
    }

    private JoinAttemptLog saveResult(CalendarEvent event, String triggerType, JoinAutomationResult result) {
        JoinAttemptLog log = JoinAttemptLog.builder()
                .calendarEventId(event.getId())
                .scheduledStart(event.getStartTime())
                .attemptedAt(LocalDateTime.now())
                .date(LocalDate.now())
                .triggerType(triggerType)
                .status(result.status().name())
                .reasonCode(result.reasonCode())
                .detailMessage(result.detailMessage())
                .build();
        return joinAttemptLogRepository.save(log);
    }

    private void maybeNotify(CalendarEvent event, JoinAttemptLog log) {
        JoinAttemptSubject subject = new JoinAttemptSubject(log.getStatus(), log.getReasonCode(), log.getDetailMessage());
        if (JoinAttemptStatus.JOINED.name().equals(log.getStatus())) {
            if (autoJoinProperties.isNotifyOnSuccess()) {
                notificationService.notify(NotificationType.AUTO_JOIN_SUCCESS, event, subject);
            }
            return;
        }
        notificationService.notify(NotificationType.AUTO_JOIN_FAILED, event, subject);
    }
}
