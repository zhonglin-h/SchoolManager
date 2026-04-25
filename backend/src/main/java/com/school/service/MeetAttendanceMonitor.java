package com.school.service;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import com.school.config.AutoJoinProperties;
import com.school.model.CalendarEvent;

import lombok.extern.slf4j.Slf4j;

/**
 * Monitors Google Meet attendance for scheduled classes.
 *
 * <p>On startup and at midnight each day, schedules four one-shot tasks per calendar event:
 * <ol>
 *   <li>T−15 min — check that the meeting room has been opened by the teacher</li>
 *   <li>T−3 min — check which expected participants have already joined</li>
 *   <li>T+0 (class start) — begin 60-second polling to record arrivals in real time</li>
 *   <li>T+duration (class end) — finalize attendance for anyone not caught by live polling</li>
 * </ol>
 *
 * <p>All attendance records and notifications are deduplicated via {@link NotificationService}
 * and {@link com.school.repository.AttendanceRepository}.
 */
@Slf4j
@Service
public class MeetAttendanceMonitor {

    private final CalendarSyncService calendarSyncService;
    private final MeetSessionHandler sessionHandler;
    private final NotificationService notificationService;
    private final ThreadPoolTaskScheduler taskScheduler;
    private final UpcomingChecksRegistry upcomingChecksRegistry;
    private final AutoJoinProperties autoJoinProperties;

    private final Map<String, List<ScheduledFuture<?>>> oneTimeFutures = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastScheduledStartTime = new ConcurrentHashMap<>();

    /** @deprecated Use {@link com.school.service.ScheduledCheck} directly. Kept for API compatibility with {@link com.school.controller.CalendarController}. */
    public record ScheduledCheck(String eventId, String eventTitle, String checkType, Instant scheduledAt) {}

    public MeetAttendanceMonitor(CalendarSyncService calendarSyncService,
                                  MeetSessionHandler sessionHandler,
                                  NotificationService notificationService,
                                  ThreadPoolTaskScheduler taskScheduler,
                                  UpcomingChecksRegistry upcomingChecksRegistry,
                                  AutoJoinProperties autoJoinProperties) {
        this.calendarSyncService = calendarSyncService;
        this.sessionHandler = sessionHandler;
        this.notificationService = notificationService;
        this.taskScheduler = taskScheduler;
        this.upcomingChecksRegistry = upcomingChecksRegistry;
        this.autoJoinProperties = autoJoinProperties;
    }

    /** Returns all checks whose scheduled time is still in the future, sorted ascending. */
    public List<MeetAttendanceMonitor.ScheduledCheck> getUpcomingChecks() {
        return upcomingChecksRegistry.getUpcoming().stream()
                .map(c -> new MeetAttendanceMonitor.ScheduledCheck(c.eventId(), c.eventTitle(), c.checkType(), c.scheduledAt()))
                .toList();
    }

    /** Triggers a full reschedule at midnight so the new day's events are picked up. */
    @Scheduled(cron = "0 0 0 * * *")
    public void scheduleDailyRefresh() {
        scheduleEventsForToday();
    }

    /** Schedules today's events immediately after the application context is fully initialized. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        scheduleEventsForToday();
    }

    /**
     * Fetches today's calendar events and schedules (or reschedules) all monitoring tasks.
     * Safe to call repeatedly — stale futures are cancelled before new ones are registered.
     */
    public void scheduleEventsForToday() {
        List<CalendarEvent> events;
        try {
            events = calendarSyncService.getTodaysEvents();
        } catch (Exception e) {
            return;
        }

        // Build the set of IDs that are still on today's calendar after this sync
        Set<String> freshEventIds = new HashSet<>();
        for (CalendarEvent e : events) {
            freshEventIds.add(e.getId());
        }

        // Cancel futures for events that were removed from or moved off today's calendar
        // Copy keyset to avoid ConcurrentModificationException while removing stale entries
        for (String staleId : new ArrayList<>(oneTimeFutures.keySet())) {
            if (!freshEventIds.contains(staleId)) {
                cancelOneTimeFutures(staleId);
                sessionHandler.cancelPollingFor(staleId);
                lastScheduledStartTime.remove(staleId);
            }
        }

        upcomingChecksRegistry.clear();
        Instant now = Instant.now();

        for (CalendarEvent event : events) {
            // Cancel any existing one-shot futures for this event (prevents duplicates on re-sync)
            cancelOneTimeFutures(event.getId());

            // Cancel active polling future — will be restarted by the new SESSION_START task
            sessionHandler.cancelPollingFor(event.getId());

            // Detect reschedule: if the start time changed, clear today's notification logs
            // so notifications can re-fire at the correct new time
            Instant newStart = event.getStartTime().atZone(ZoneId.systemDefault()).toInstant();
            Instant previousStart = lastScheduledStartTime.get(event.getId());
            if (previousStart != null && !previousStart.equals(newStart)) {
                notificationService.clearTodayLogsForEvent(event.getId());
                log.info("Event '{}' rescheduled; cleared today's notification logs", event.getTitle());
            }
            lastScheduledStartTime.put(event.getId(), newStart);

            List<ScheduledFuture<?>> futures = new ArrayList<>();

            Instant minus15 = event.getStartTime().minusMinutes(15)
                    .atZone(ZoneId.systemDefault()).toInstant();
            Instant minus3 = event.getStartTime().minusMinutes(3)
                    .atZone(ZoneId.systemDefault()).toInstant();
            Instant start = event.getStartTime()
                    .atZone(ZoneId.systemDefault()).toInstant();
            Instant end = event.getEndTime()
                    .atZone(ZoneId.systemDefault()).toInstant();
            Instant autoJoinTrigger = start.minusSeconds(Math.max(0, autoJoinProperties.getTriggerOffsetSeconds()));

            if (minus15.isAfter(now)) {
                upcomingChecksRegistry.add(new com.school.service.ScheduledCheck(event.getId(), event.getTitle(), "MEETING_NOT_STARTED_15", minus15));
                futures.add(taskScheduler.schedule(() -> {
                    upcomingChecksRegistry.remove(event.getId(), "MEETING_NOT_STARTED_15");
                    sessionHandler.checkMeetingStarted(event, NotificationType.MEETING_NOT_STARTED_15);
                }, minus15));
            }
            if (minus3.isAfter(now)) {
                upcomingChecksRegistry.add(new com.school.service.ScheduledCheck(event.getId(), event.getTitle(), "PRE_CLASS_JOINS", minus3));
                futures.add(taskScheduler.schedule(() -> {
                    upcomingChecksRegistry.remove(event.getId(), "PRE_CLASS_JOINS");
                    sessionHandler.checkPreClassJoins(event);
                }, minus3));
            }
            if (start.isAfter(now)) {
                if (autoJoinProperties.isEnabled()) {
                    Instant triggerTime = autoJoinTrigger.isAfter(now) ? autoJoinTrigger : Instant.now().plusSeconds(1);
                    upcomingChecksRegistry.add(new com.school.service.ScheduledCheck(event.getId(), event.getTitle(), "AUTO_JOIN", triggerTime));
                    futures.add(taskScheduler.schedule(() -> {
                        upcomingChecksRegistry.remove(event.getId(), "AUTO_JOIN");
                        sessionHandler.attemptAutoJoin(event);
                    }, triggerTime));
                }
                upcomingChecksRegistry.add(new com.school.service.ScheduledCheck(event.getId(), event.getTitle(), "SESSION_START", start));
                futures.add(taskScheduler.schedule(() -> {
                    upcomingChecksRegistry.remove(event.getId(), "SESSION_START");
                    upcomingChecksRegistry.add(new com.school.service.ScheduledCheck(event.getId(), event.getTitle(), "SESSION_POLLING", end));
                    sessionHandler.startSessionPolling(event);
                }, start));
            } else if (end.isAfter(now)) {
                // Session already started but not yet ended: catch up on any missed polling
                upcomingChecksRegistry.add(new com.school.service.ScheduledCheck(event.getId(), event.getTitle(), "SESSION_POLLING", end));
                sessionHandler.resumeSessionPolling(event);
            }

            if (end.isAfter(now)) {
                upcomingChecksRegistry.add(new com.school.service.ScheduledCheck(event.getId(), event.getTitle(), "SESSION_FINALIZE", end));
                futures.add(taskScheduler.schedule(() -> {
                    upcomingChecksRegistry.remove(event.getId(), "SESSION_FINALIZE");
                    sessionHandler.finalizeSession(event);
                }, end));
            }

            oneTimeFutures.put(event.getId(), futures);
        }
    }

    /**
     * Cancels all one-time scheduled check futures for the given event and removes them from
     * the tracking map. Called before rescheduling an event and when an event is removed from
     * today's calendar, to prevent stale tasks from firing at outdated times.
     */
    private void cancelOneTimeFutures(String eventId) {
        List<ScheduledFuture<?>> futures = oneTimeFutures.remove(eventId);
        if (futures != null) {
            futures.forEach(f -> f.cancel(false));
        }
        upcomingChecksRegistry.removePollingEntry(eventId);
    }
}
