package com.school.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import com.school.model.CalendarEvent;

@ExtendWith(MockitoExtension.class)
class MeetAttendanceMonitorTest {

    @Mock CalendarSyncService calendarSyncService;
    @Mock MeetSessionHandler sessionHandler;
    @Mock NotificationService notificationService;
    @Mock ThreadPoolTaskScheduler taskScheduler;
    @InjectMocks
    MeetAttendanceMonitor monitor;

    @BeforeEach
    void setUp() {
        // nothing shared across all tests
    }

    // --- scheduleEventsForToday: upcoming checks ---

    @Test
    @SuppressWarnings("unchecked")
    void scheduleEventsForToday_addsUpcomingChecksForFutureEvents() throws Exception {
        CalendarEvent future = new CalendarEvent("evt-future", "Future Class",
                "https://meet.google.com/xyz", "xyz",
                LocalDateTime.now().plusHours(2), LocalDateTime.now().plusHours(3),
                List.of("alice@meet.com"));
        when(calendarSyncService.getTodaysEvents()).thenReturn(List.of(future));
        when(taskScheduler.schedule(any(Runnable.class), any(java.time.Instant.class)))
                .thenReturn(mock(ScheduledFuture.class));

        monitor.scheduleEventsForToday();

        List<MeetAttendanceMonitor.ScheduledCheck> checks = monitor.getUpcomingChecks();
        assertThat(checks).isNotEmpty();
        assertThat(checks).allMatch(c -> c.eventId().equals("evt-future"));
        assertThat(checks.stream().map(MeetAttendanceMonitor.ScheduledCheck::checkType))
                .contains("MEETING_NOT_STARTED_15", "PRE_CLASS_JOINS", "SESSION_START", "SESSION_FINALIZE");
    }

    @Test
    @SuppressWarnings("unchecked")
    void scheduleEventsForToday_clearsOldChecksOnRefresh() throws Exception {
        CalendarEvent future = new CalendarEvent("evt-a", "Class A",
                "https://meet.google.com/aaa", "aaa",
                LocalDateTime.now().plusHours(2), LocalDateTime.now().plusHours(3),
                List.of());
        when(calendarSyncService.getTodaysEvents()).thenReturn(List.of(future));
        when(taskScheduler.schedule(any(Runnable.class), any(java.time.Instant.class)))
                .thenReturn(mock(ScheduledFuture.class));

        monitor.scheduleEventsForToday();
        int firstCount = monitor.getUpcomingChecks().size();

        when(calendarSyncService.getTodaysEvents()).thenReturn(List.of());
        monitor.scheduleEventsForToday();

        assertThat(firstCount).isGreaterThan(0);
        assertThat(monitor.getUpcomingChecks()).isEmpty();
    }

    // --- scheduleEventsForToday: future cancellation and reschedule handling ---

    @Test
    @SuppressWarnings("unchecked")
    void scheduleEventsForToday_cancelsExistingOneTimeFuturesOnReSyncOfSameEvent() throws Exception {
        CalendarEvent futureEvent = new CalendarEvent("evt-resync", "Resync Class",
                "https://meet.google.com/xyz", "xyz",
                LocalDateTime.now().plusHours(2), LocalDateTime.now().plusHours(3),
                List.of());
        ScheduledFuture firstFuture = mock(ScheduledFuture.class);
        when(calendarSyncService.getTodaysEvents()).thenReturn(List.of(futureEvent));
        when(taskScheduler.schedule(any(Runnable.class), any(java.time.Instant.class)))
                .thenReturn(firstFuture);
        monitor.scheduleEventsForToday();

        when(taskScheduler.schedule(any(Runnable.class), any(java.time.Instant.class)))
                .thenReturn(mock(ScheduledFuture.class));
        monitor.scheduleEventsForToday();

        verify(firstFuture, atLeastOnce()).cancel(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void scheduleEventsForToday_cancelsOneTimeFuturesForRemovedEvents() throws Exception {
        CalendarEvent futureEvent = new CalendarEvent("evt-gone", "Gone Class",
                "https://meet.google.com/xyz", "xyz",
                LocalDateTime.now().plusHours(2), LocalDateTime.now().plusHours(3),
                List.of());
        ScheduledFuture goneFuture = mock(ScheduledFuture.class);
        when(calendarSyncService.getTodaysEvents()).thenReturn(List.of(futureEvent));
        when(taskScheduler.schedule(any(Runnable.class), any(java.time.Instant.class)))
                .thenReturn(goneFuture);
        monitor.scheduleEventsForToday();

        when(calendarSyncService.getTodaysEvents()).thenReturn(List.of());
        monitor.scheduleEventsForToday();

        verify(goneFuture, atLeastOnce()).cancel(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void scheduleEventsForToday_clearsNotificationLogsWhenEventStartTimeChanges() throws Exception {
        CalendarEvent original = new CalendarEvent("evt-rescheduled", "Math Class",
                "https://meet.google.com/xyz", "xyz",
                LocalDateTime.now().plusHours(2), LocalDateTime.now().plusHours(3),
                List.of());
        when(calendarSyncService.getTodaysEvents()).thenReturn(List.of(original));
        when(taskScheduler.schedule(any(Runnable.class), any(java.time.Instant.class)))
                .thenReturn(mock(ScheduledFuture.class));
        monitor.scheduleEventsForToday();

        CalendarEvent rescheduled = new CalendarEvent("evt-rescheduled", "Math Class",
                "https://meet.google.com/xyz", "xyz",
                LocalDateTime.now().plusHours(3), LocalDateTime.now().plusHours(4),
                List.of());
        when(calendarSyncService.getTodaysEvents()).thenReturn(List.of(rescheduled));
        monitor.scheduleEventsForToday();

        verify(notificationService).clearTodayLogsForEvent("evt-rescheduled");
    }

    @Test
    @SuppressWarnings("unchecked")
    void scheduleEventsForToday_doesNotClearLogsWhenStartTimeUnchanged() throws Exception {
        CalendarEvent sameEvent = new CalendarEvent("evt-same", "Math Class",
                "https://meet.google.com/xyz", "xyz",
                LocalDateTime.now().plusHours(2), LocalDateTime.now().plusHours(3),
                List.of());
        when(calendarSyncService.getTodaysEvents()).thenReturn(List.of(sameEvent));
        when(taskScheduler.schedule(any(Runnable.class), any(java.time.Instant.class)))
                .thenReturn(mock(ScheduledFuture.class));

        monitor.scheduleEventsForToday();
        monitor.scheduleEventsForToday();

        verify(notificationService, never()).clearTodayLogsForEvent(anyString());
    }

    // --- scheduleEventsForToday: partial future checkpoints ---

    @Test
    @SuppressWarnings("unchecked")
    void scheduleEventsForToday_skipsT15ButSchedulesT3WhenMeetingIsMinutesAway() throws Exception {
        // Meeting 4 minutes away: T-15 is already past, T-3 is 1 minute in the future.
        // Only PRE_CLASS_JOINS, SESSION_START, and SESSION_FINALIZE should appear in upcomingChecks.
        CalendarEvent soon = new CalendarEvent("evt-soon", "Imminent Class",
                "https://meet.google.com/abc", "abc",
                LocalDateTime.now().plusMinutes(4), LocalDateTime.now().plusMinutes(64),
                List.of());
        when(calendarSyncService.getTodaysEvents()).thenReturn(List.of(soon));
        when(taskScheduler.schedule(any(Runnable.class), any(java.time.Instant.class)))
                .thenReturn(mock(ScheduledFuture.class));

        monitor.scheduleEventsForToday();

        List<MeetAttendanceMonitor.ScheduledCheck> checks = monitor.getUpcomingChecks();
        List<String> types = checks.stream().map(MeetAttendanceMonitor.ScheduledCheck::checkType).toList();
        assertThat(types).doesNotContain("MEETING_NOT_STARTED_15");
        assertThat(types).contains("PRE_CLASS_JOINS", "SESSION_START", "SESSION_FINALIZE");
    }

    @Test
    @SuppressWarnings("unchecked")
    void scheduleEventsForToday_rescheduledToSoonStillGetsFreshT3Check() throws Exception {
        // Simulate the "Refresh Live" bug: event initially far away (T-3 and T-15 both future),
        // then rescheduled to 4 minutes from now (T-15 past, T-3 still future).
        // After re-sync, a fresh PRE_CLASS_JOINS check must be registered.
        CalendarEvent original = new CalendarEvent("evt-moved", "Math Class",
                "https://meet.google.com/abc", "abc",
                LocalDateTime.now().plusHours(2), LocalDateTime.now().plusHours(3),
                List.of());
        when(calendarSyncService.getTodaysEvents()).thenReturn(List.of(original));
        when(taskScheduler.schedule(any(Runnable.class), any(java.time.Instant.class)))
                .thenReturn(mock(ScheduledFuture.class));
        monitor.scheduleEventsForToday();

        CalendarEvent moved = new CalendarEvent("evt-moved", "Math Class",
                "https://meet.google.com/abc", "abc",
                LocalDateTime.now().plusMinutes(4), LocalDateTime.now().plusMinutes(64),
                List.of());
        when(calendarSyncService.getTodaysEvents()).thenReturn(List.of(moved));
        monitor.scheduleEventsForToday();

        List<MeetAttendanceMonitor.ScheduledCheck> checks = monitor.getUpcomingChecks();
        List<String> types = checks.stream().map(MeetAttendanceMonitor.ScheduledCheck::checkType).toList();
        assertThat(types).doesNotContain("MEETING_NOT_STARTED_15");
        assertThat(types).contains("PRE_CLASS_JOINS");
        verify(notificationService).clearTodayLogsForEvent("evt-moved");
    }

    // --- scheduleEventsForToday: in-progress and ended events ---

    @Test
    @SuppressWarnings("unchecked")
    void scheduleEventsForToday_callsResumeSessionPollingForInProgressEvent() throws Exception {
        CalendarEvent inProgress = new CalendarEvent("evt-inprogress", "In-Progress Class",
                "https://meet.google.com/abc-def", "abc-def",
                LocalDateTime.now().minusMinutes(10), LocalDateTime.now().plusMinutes(50),
                List.of("alice@meet.com", "bob@meet.com"));
        when(calendarSyncService.getTodaysEvents()).thenReturn(List.of(inProgress));
        when(taskScheduler.schedule(any(Runnable.class), any(java.time.Instant.class)))
                .thenReturn(mock(ScheduledFuture.class));

        monitor.scheduleEventsForToday();

        verify(sessionHandler).resumeSessionPolling(inProgress);
        List<MeetAttendanceMonitor.ScheduledCheck> checks = monitor.getUpcomingChecks();
        assertThat(checks).anyMatch(c -> c.eventId().equals("evt-inprogress") && c.checkType().equals("SESSION_FINALIZE"));
        assertThat(checks).noneMatch(c -> c.eventId().equals("evt-inprogress") && c.checkType().equals("SESSION_START"));
    }

    @Test
    void scheduleEventsForToday_doesNotCallResumeSessionPollingForAlreadyEndedEvent() throws Exception {
        CalendarEvent ended = new CalendarEvent("evt-ended", "Ended Class",
                "https://meet.google.com/abc-def", "abc-def",
                LocalDateTime.now().minusMinutes(65), LocalDateTime.now().minusMinutes(5),
                List.of("alice@meet.com"));
        when(calendarSyncService.getTodaysEvents()).thenReturn(List.of(ended));

        monitor.scheduleEventsForToday();

        verify(sessionHandler, never()).resumeSessionPolling(ended);
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(java.time.Instant.class));
    }
}
