package com.school.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.school.entity.JoinAttemptLog;
import com.school.entity.JoinAttemptStatus;
import com.school.integration.JoinAutomationClient;
import com.school.integration.JoinResult;
import com.school.model.CalendarEvent;
import com.school.repository.JoinAttemptLogRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Orchestrates best-effort auto-join attempts for Google Meet sessions.
 *
 * <p>Call {@link #attemptJoinIfEnabled} from the scheduler (respects the enabled flag).
 * Call {@link #attemptJoin} directly for manual/admin triggers (always attempts).
 *
 * <p>Both methods are idempotent per {@code (calendarEventId, date, triggerType)}: a second
 * call with the same key returns the existing log entry without invoking the automation client.
 */
@Slf4j
@Service
public class JoinAttemptService {

    private final JoinAutomationClient joinAutomationClient;
    private final JoinAttemptLogRepository joinAttemptLogRepository;
    private final NotificationService notificationService;

    @Value("${app.autojoin.enabled:false}")
    private boolean enabled;

    @Value("${app.autojoin.notify-on-success:false}")
    private boolean notifyOnSuccess;

    public JoinAttemptService(JoinAutomationClient joinAutomationClient,
                               JoinAttemptLogRepository joinAttemptLogRepository,
                               NotificationService notificationService) {
        this.joinAutomationClient = joinAutomationClient;
        this.joinAttemptLogRepository = joinAttemptLogRepository;
        this.notificationService = notificationService;
    }

    /**
     * Attempts to join the Meet session only when auto-join is enabled
     * ({@code app.autojoin.enabled=true}).  Used by the scheduled trigger in
     * {@link MeetAttendanceMonitor}.
     *
     * @param event       the calendar event whose Meet session should be joined
     * @param triggerType identifier for this trigger source (e.g. {@code "AUTO"})
     */
    public void attemptJoinIfEnabled(CalendarEvent event, String triggerType) {
        if (!enabled) {
            log.debug("Auto-join is disabled; skipping join for event '{}'", event.getTitle());
            return;
        }
        attemptJoin(event, triggerType);
    }

    /**
     * Unconditionally attempts to join the Meet session, regardless of the enabled flag.
     * Used for manual/admin-triggered joins.
     *
     * <p>Idempotent: if a log entry already exists for
     * {@code (calendarEventId, today, triggerType)}, the existing entry is returned
     * without invoking the automation client again.
     *
     * @param event       the calendar event whose Meet session should be joined
     * @param triggerType identifier for this trigger source (e.g. {@code "MANUAL"})
     * @return the created or existing {@link JoinAttemptLog}
     */
    @Transactional
    public JoinAttemptLog attemptJoin(CalendarEvent event, String triggerType) {
        LocalDate today = LocalDate.now();

        // Idempotency: return existing attempt if already performed today for this trigger
        Optional<JoinAttemptLog> existing = joinAttemptLogRepository
                .findByCalendarEventIdAndDateAndTriggerType(event.getId(), today, triggerType);
        if (existing.isPresent()) {
            log.debug("Join attempt already recorded for event '{}' triggerType='{}'; skipping",
                    event.getTitle(), triggerType);
            return existing.get();
        }

        // Guard: event must have a Meet link and space code
        if (event.getMeetLink() == null || event.getSpaceCode() == null) {
            log.warn("Event '{}' has no Meet link or space code; recording failed attempt", event.getTitle());
            return saveAndNotify(event, triggerType, today,
                    new JoinResult(JoinAttemptStatus.FAILED_UNKNOWN,
                            "Event has no Meet link or space code"));
        }

        // Invoke the automation client
        JoinResult result;
        try {
            log.info("Invoking join automation for event '{}' (triggerType={})", event.getTitle(), triggerType);
            result = joinAutomationClient.attemptJoin(event);
        } catch (Exception e) {
            log.error("Join automation threw an exception for event '{}': {}", event.getTitle(), e.getMessage());
            result = new JoinResult(JoinAttemptStatus.FAILED_UNKNOWN, e.getMessage());
        }

        return saveAndNotify(event, triggerType, today, result);
    }

    /**
     * Returns all join attempt log entries for today, newest first.
     */
    public List<JoinAttemptLog> getTodayAttempts() {
        return joinAttemptLogRepository.findByDateOrderByAttemptedAtDesc(LocalDate.now());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private JoinAttemptLog saveAndNotify(CalendarEvent event, String triggerType,
                                          LocalDate date, JoinResult result) {
        JoinAttemptLog log = JoinAttemptLog.builder()
                .calendarEventId(event.getId())
                .scheduledStart(event.getStartTime())
                .attemptedAt(LocalDateTime.now())
                .status(result.status())
                .detailMessage(result.detailMessage())
                .date(date)
                .triggerType(triggerType)
                .build();

        JoinAttemptLog saved = joinAttemptLogRepository.save(log);
        JoinAttemptService.log.info("Join attempt recorded for event '{}': status={}, detail={}",
                event.getTitle(), result.status(), result.detailMessage());

        sendNotification(event, result);
        return saved;
    }

    private void sendNotification(CalendarEvent event, JoinResult result) {
        if (result.status() == JoinAttemptStatus.JOINED) {
            if (notifyOnSuccess) {
                notificationService.notify(NotificationType.AUTO_JOIN_SUCCESS, event, null);
            }
        } else {
            notificationService.notify(NotificationType.AUTO_JOIN_FAILED, event, null);
        }
    }
}
