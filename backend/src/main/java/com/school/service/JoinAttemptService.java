package com.school.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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
 * <p>Each invocation records its own timestamped log entry.
 */
@Slf4j
@Service
public class JoinAttemptService {

    private final JoinAutomationClient joinAutomationClient;
    private final JoinAttemptLogRepository joinAttemptLogRepository;
    private final NotificationService notificationService;

    @Value("${app.autojoin.enabled:false}")
    private boolean enabled;

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
        log.info("Received join request: triggerType={}, eventId={}, title='{}', autoJoinEnabled={}",
                triggerType, event.getId(), event.getTitle(), enabled);
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
     * @param event       the calendar event whose Meet session should be joined
     * @param triggerType identifier for this trigger source (e.g. {@code "MANUAL"})
     * @return the created {@link JoinAttemptLog}
     */
    @Transactional
    public JoinAttemptLog attemptJoin(CalendarEvent event, String triggerType) {
        // Guard: event must have a Meet link and space code
        if (event.getMeetLink() == null || event.getMeetLink().isBlank()
                || event.getSpaceCode() == null || event.getSpaceCode().isBlank()) {
            log.warn("Event '{}' has no Meet link or space code; recording failed attempt", event.getTitle());
            return saveAndNotify(event, triggerType,
                    new JoinResult(JoinAttemptStatus.FAILED_UNKNOWN,
                            "Event has no Meet link or space code"));
        }

        // Invoke the automation client
        JoinResult result;
        try {
            log.info("Invoking join automation for event '{}' (id={}, triggerType={}, spaceCode={}, meetLink={})",
                    event.getTitle(), event.getId(), triggerType, event.getSpaceCode(), event.getMeetLink());
            result = joinAutomationClient.attemptJoin(event);
        } catch (Exception e) {
            log.error("Join automation threw an exception for event '{}': {}", event.getTitle(), e.getMessage());
            result = new JoinResult(JoinAttemptStatus.FAILED_UNKNOWN, e.getMessage());
        }

        log.info("Join automation completed for event '{}' (id={}, triggerType={}): status={}, detail={}",
                event.getTitle(), event.getId(), triggerType, result.status(), result.detailMessage());

        return saveAndNotify(event, triggerType, result);
    }

    /**
     * Returns all join attempt log entries for today, newest first.
     */
    public List<JoinAttemptLog> getTodayAttempts() {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        LocalDateTime startOfTomorrow = startOfDay.plusDays(1);
        return joinAttemptLogRepository.findByAttemptedAtBetweenOrderByAttemptedAtDesc(startOfDay, startOfTomorrow);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private JoinAttemptLog saveAndNotify(CalendarEvent event, String triggerType, JoinResult result) {
        JoinAttemptLog entry = JoinAttemptLog.builder()
                .calendarEventId(event.getId())
                .scheduledStart(event.getStartTime())
                .attemptedAt(LocalDateTime.now())
                .status(result.status())
                .detailMessage(result.detailMessage())
                .triggerType(triggerType)
                .build();

        JoinAttemptLog saved = joinAttemptLogRepository.save(entry);
        log.info("Join attempt recorded for event '{}': status={}, detail={}",
                event.getTitle(), result.status(), result.detailMessage());

        if (isAutoTrigger(triggerType)) {
            sendNotification(event, result);
        }
        return saved;
    }

    private void sendNotification(CalendarEvent event, JoinResult result) {
        if (isSuccessfulAutoJoinStatus(result.status())) {
            notificationService.notify(NotificationType.AUTO_JOIN_SUCCESS, event, null);
        } else {
            notificationService.notify(NotificationType.AUTO_JOIN_FAILED, event, null);
        }
    }

    private boolean isAutoTrigger(String triggerType) {
        return "AUTO".equalsIgnoreCase(triggerType);
    }

    private boolean isSuccessfulAutoJoinStatus(JoinAttemptStatus status) {
        return status == JoinAttemptStatus.JOINED
                || status == JoinAttemptStatus.ALREADY_OPEN_ELSEWHERE;
    }
}
