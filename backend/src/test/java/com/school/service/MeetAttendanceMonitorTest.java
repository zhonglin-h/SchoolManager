package com.school.service;

import com.school.entity.Attendance;
import com.school.entity.AttendanceStatus;
import com.school.entity.Student;
import com.school.entity.Teacher;
import com.school.integration.MeetClient;
import com.school.integration.MeetParticipant;
import com.school.model.CalendarEvent;
import com.school.repository.AttendanceRepository;
import com.school.repository.StudentRepository;
import com.school.repository.TeacherRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MeetAttendanceMonitorTest {

    @Mock CalendarSyncService calendarSyncService;
    @Mock StudentRepository studentRepository;
    @Mock TeacherRepository teacherRepository;
    @Mock AttendanceRepository attendanceRepository;
    @Mock NotificationService notificationService;
    @Mock MeetClient meetClient;
    @Mock ThreadPoolTaskScheduler taskScheduler;
    @InjectMocks
    MeetAttendanceMonitor monitor;

    private CalendarEvent event;
    private Student alice;
    private Student bob;
    private Teacher carol;

    private static final MeetParticipant ALICE_PARTICIPANT = new MeetParticipant("uid-alice", "Alice", null);
    private static final MeetParticipant BOB_PARTICIPANT   = new MeetParticipant("uid-bob",   "Bob",   null);
    private static final MeetParticipant CAROL_PARTICIPANT = new MeetParticipant("uid-carol", "Carol", null);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(monitor, "lateBufferMinutes", 5);

        event = new CalendarEvent("evt-1", "Math Class",
                "https://meet.google.com/abc-def", "abc-def",
                LocalDateTime.now(), LocalDateTime.now().plusHours(1),
                List.of("alice@meet.com", "bob@meet.com"));

        alice = Student.builder().id(1L).name("Alice").meetEmail("alice@meet.com")
                .parentEmail("alice-p@test.com").build();
        bob = Student.builder().id(2L).name("Bob").meetEmail("bob@meet.com")
                .parentEmail("bob-p@test.com").build();
        carol = Teacher.builder().id(10L).name("Carol").meetEmail("carol@meet.com").build();
    }

    // --- checkMeetingStarted ---

    @Test
    void checkMeetingStarted_notifiesPrincipalWhenMeetingNotActive() throws Exception {
        when(meetClient.isMeetingActive("abc-def")).thenReturn(false);

        monitor.checkMeetingStarted(event, NotificationType.MEETING_NOT_STARTED_15);

        verify(notificationService).notify(NotificationType.MEETING_NOT_STARTED_15, event, null);
    }

    @Test
    void checkMeetingStarted_doesNothingWhenMeetingIsActive() throws Exception {
        when(meetClient.isMeetingActive("abc-def")).thenReturn(true);

        monitor.checkMeetingStarted(event, NotificationType.MEETING_NOT_STARTED_15);

        verify(notificationService, never()).notify(any(), any(), any());
    }

    @Test
    void checkMeetingStarted_swallowsExceptionGracefully() throws Exception {
        when(meetClient.isMeetingActive(anyString())).thenThrow(new RuntimeException("API error"));

        monitor.checkMeetingStarted(event, NotificationType.MEETING_NOT_STARTED_15);

        verify(notificationService, never()).notify(any(), any(), any());
    }

    // --- resolveAndAutoLearn (via checkPreClassJoins) ---

    @Test
    void checkPreClassJoins_matchesByGoogleUserId() throws Exception {
        when(meetClient.getActiveParticipants("abc-def")).thenReturn(List.of(ALICE_PARTICIPANT));
        when(studentRepository.findByMeetEmailAndActiveTrue("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmailAndActiveTrue("bob@meet.com")).thenReturn(Optional.of(bob));
        when(studentRepository.findByGoogleUserIdAndActiveTrue("uid-alice")).thenReturn(Optional.of(alice));

        monitor.checkPreClassJoins(event);

        verify(notificationService, never()).notify(eq(NotificationType.NOT_YET_JOINED_3), eq(event), eq(new StudentRecipient(alice)));
        verify(notificationService).notify(NotificationType.NOT_YET_JOINED_3, event, new StudentRecipient(bob));
    }

    @Test
    void checkPreClassJoins_fallsBackToDisplayNameWhenNoGoogleUserId() throws Exception {
        MeetParticipant noId = new MeetParticipant(null, "Alice", null);
        when(meetClient.getActiveParticipants("abc-def")).thenReturn(List.of(noId));
        when(studentRepository.findByMeetEmailAndActiveTrue("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmailAndActiveTrue("bob@meet.com")).thenReturn(Optional.of(bob));
        when(studentRepository.findByNameIgnoreCaseAndActiveTrue("Alice")).thenReturn(Optional.of(alice));

        monitor.checkPreClassJoins(event);

        verify(notificationService, never()).notify(eq(NotificationType.NOT_YET_JOINED_3), eq(event), eq(new StudentRecipient(alice)));
        verify(notificationService).notify(NotificationType.NOT_YET_JOINED_3, event, new StudentRecipient(bob));
    }

    @Test
    void checkPreClassJoins_autoSavesGoogleUserIdOnNameMatch() throws Exception {
        // Alice has no googleUserId stored yet; participant arrives with one
        MeetParticipant newParticipant = new MeetParticipant("uid-alice", "Alice", null);
        when(meetClient.getActiveParticipants("abc-def")).thenReturn(List.of(newParticipant));
        when(studentRepository.findByMeetEmailAndActiveTrue("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmailAndActiveTrue("bob@meet.com")).thenReturn(Optional.of(bob));
        when(studentRepository.findByGoogleUserIdAndActiveTrue("uid-alice")).thenReturn(Optional.empty());
        when(studentRepository.findByNameIgnoreCaseAndActiveTrue("Alice")).thenReturn(Optional.of(alice));

        monitor.checkPreClassJoins(event);

        ArgumentCaptor<Student> captor = ArgumentCaptor.forClass(Student.class);
        verify(studentRepository).save(captor.capture());
        assertThat(captor.getValue().getGoogleUserId()).isEqualTo("uid-alice");
    }

    @Test
    void checkPreClassJoins_doesNotNotifyWhenAllPresent() throws Exception {
        when(meetClient.getActiveParticipants("abc-def")).thenReturn(List.of(ALICE_PARTICIPANT, BOB_PARTICIPANT));
        when(studentRepository.findByMeetEmailAndActiveTrue("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmailAndActiveTrue("bob@meet.com")).thenReturn(Optional.of(bob));
        when(studentRepository.findByGoogleUserIdAndActiveTrue("uid-alice")).thenReturn(Optional.of(alice));
        when(studentRepository.findByGoogleUserIdAndActiveTrue("uid-bob")).thenReturn(Optional.of(bob));

        monitor.checkPreClassJoins(event);

        verify(notificationService, never()).notify(eq(NotificationType.NOT_YET_JOINED_3), any(), any());
    }

    @Test
    void checkPreClassJoins_skipsAttendeesNotInStudentRegistry() throws Exception {
        when(meetClient.getActiveParticipants("abc-def")).thenReturn(List.of());
        when(studentRepository.findByMeetEmailAndActiveTrue("alice@meet.com")).thenReturn(Optional.empty());
        when(studentRepository.findByMeetEmailAndActiveTrue("bob@meet.com")).thenReturn(Optional.of(bob));

        monitor.checkPreClassJoins(event);

        verify(notificationService).notify(NotificationType.NOT_YET_JOINED_3, event, new StudentRecipient(bob));
        verify(notificationService, never()).notify(eq(NotificationType.NOT_YET_JOINED_3), eq(event), eq(new StudentRecipient(alice)));
    }

    // --- startSessionPolling (initial snapshot) ---

    @Test
    @SuppressWarnings("unchecked")
    void startSessionPolling_recordsPresentForStudentsAlreadyInMeeting() throws Exception {
        when(meetClient.isMeetingActive("abc-def")).thenReturn(true);
        when(meetClient.getActiveParticipants("abc-def")).thenReturn(List.of(ALICE_PARTICIPANT, BOB_PARTICIPANT));
        when(studentRepository.findByMeetEmailAndActiveTrue("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmailAndActiveTrue("bob@meet.com")).thenReturn(Optional.of(bob));
        when(studentRepository.findByGoogleUserIdAndActiveTrue("uid-alice")).thenReturn(Optional.of(alice));
        when(studentRepository.findByGoogleUserIdAndActiveTrue("uid-bob")).thenReturn(Optional.of(bob));
        when(attendanceRepository.findByStudentIdAndCalendarEventIdAndDate(anyLong(), anyString(), any()))
                .thenReturn(Optional.empty());
        when(taskScheduler.scheduleAtFixedRate(any(Runnable.class), any(Duration.class)))
                .thenReturn(mock(ScheduledFuture.class));

        monitor.startSessionPolling(event);

        ArgumentCaptor<Attendance> captor = ArgumentCaptor.forClass(Attendance.class);
        verify(attendanceRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).allMatch(a -> a.getStatus() == AttendanceStatus.PRESENT);
    }

    @Test
    @SuppressWarnings("unchecked")
    void startSessionPolling_doesNotDuplicateAttendanceIfAlreadyRecorded() throws Exception {
        when(meetClient.isMeetingActive("abc-def")).thenReturn(true);
        when(meetClient.getActiveParticipants("abc-def")).thenReturn(List.of(ALICE_PARTICIPANT));
        when(studentRepository.findByMeetEmailAndActiveTrue("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmailAndActiveTrue("bob@meet.com")).thenReturn(Optional.of(bob));
        when(studentRepository.findByGoogleUserIdAndActiveTrue("uid-alice")).thenReturn(Optional.of(alice));
        when(attendanceRepository.findByStudentIdAndCalendarEventIdAndDate(eq(1L), anyString(), any()))
                .thenReturn(Optional.of(Attendance.builder().student(alice)
                        .calendarEventId("evt-1").date(LocalDate.now())
                        .status(AttendanceStatus.PRESENT).build()));
        when(taskScheduler.scheduleAtFixedRate(any(Runnable.class), any(Duration.class)))
                .thenReturn(mock(ScheduledFuture.class));

        monitor.startSessionPolling(event);

        verify(attendanceRepository, never()).save(argThat(a -> a.getStudent().equals(alice)));
    }

    // --- startSessionPolling (polling tick) ---

    @Test
    @SuppressWarnings("unchecked")
    void pollingTick_marksLateAndNotifiesForDelayedJoiner() throws Exception {
        ReflectionTestUtils.setField(monitor, "lateBufferMinutes", 0);
        when(meetClient.isMeetingActive("abc-def")).thenReturn(true);
        when(meetClient.getActiveParticipants("abc-def"))
                .thenReturn(List.of())                                   // initial snapshot: empty
                .thenReturn(List.of(ALICE_PARTICIPANT));                  // first poll: Alice joins
        when(studentRepository.findByMeetEmailAndActiveTrue("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmailAndActiveTrue("bob@meet.com")).thenReturn(Optional.of(bob));
        when(studentRepository.findByGoogleUserIdAndActiveTrue("uid-alice")).thenReturn(Optional.of(alice));
        when(attendanceRepository.findByStudentIdAndCalendarEventIdAndDate(anyLong(), anyString(), any()))
                .thenReturn(Optional.empty());

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        when(taskScheduler.scheduleAtFixedRate(runnableCaptor.capture(), any(Duration.class)))
                .thenReturn(mock(ScheduledFuture.class));

        monitor.startSessionPolling(event);
        runnableCaptor.getValue().run();

        ArgumentCaptor<Attendance> attendanceCaptor = ArgumentCaptor.forClass(Attendance.class);
        verify(attendanceRepository, org.mockito.Mockito.atLeastOnce()).save(attendanceCaptor.capture());
        assertThat(attendanceCaptor.getAllValues())
                .anyMatch(a -> a.getStudent().equals(alice) && a.getStatus() == AttendanceStatus.LATE);
        verify(notificationService).notify(NotificationType.LATE, event, new StudentRecipient(alice));
    }

    @Test
    @SuppressWarnings("unchecked")
    void pollingTick_notifiesAllPresentWhenEveryoneAccountedFor() throws Exception {
        when(meetClient.isMeetingActive("abc-def")).thenReturn(true);
        when(meetClient.getActiveParticipants("abc-def"))
                .thenReturn(List.of(ALICE_PARTICIPANT, BOB_PARTICIPANT));
        when(studentRepository.findByMeetEmailAndActiveTrue("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmailAndActiveTrue("bob@meet.com")).thenReturn(Optional.of(bob));
        when(studentRepository.findByGoogleUserIdAndActiveTrue("uid-alice")).thenReturn(Optional.of(alice));
        when(studentRepository.findByGoogleUserIdAndActiveTrue("uid-bob")).thenReturn(Optional.of(bob));
        when(attendanceRepository.findByStudentIdAndCalendarEventIdAndDate(anyLong(), anyString(), any()))
                .thenReturn(Optional.empty());

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        when(taskScheduler.scheduleAtFixedRate(runnableCaptor.capture(), any(Duration.class)))
                .thenReturn(mock(ScheduledFuture.class));

        monitor.startSessionPolling(event);
        runnableCaptor.getValue().run();

        verify(notificationService).notify(NotificationType.ALL_PRESENT, event, null);
    }

    // --- meeting-not-started Telegram reminders ---

    @Test
    @SuppressWarnings("unchecked")
    void startSessionPolling_notifiesViaMeetingNotStarted15WhenMeetingNotActive() throws Exception {
        when(meetClient.isMeetingActive("abc-def")).thenReturn(false);
        when(studentRepository.findByMeetEmailAndActiveTrue("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmailAndActiveTrue("bob@meet.com")).thenReturn(Optional.of(bob));
        when(taskScheduler.scheduleAtFixedRate(any(Runnable.class), any(Duration.class)))
                .thenReturn(mock(ScheduledFuture.class));

        monitor.startSessionPolling(event);

        verify(notificationService).notify(eq(NotificationType.MEETING_NOT_STARTED_15), eq(event), isNull());
        verify(meetClient, never()).getActiveParticipants(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void pollingTick_notifiesViaMeetingNotStarted15EachTickWhileMeetingInactive() throws Exception {
        when(meetClient.isMeetingActive("abc-def")).thenReturn(false);
        when(studentRepository.findByMeetEmailAndActiveTrue("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmailAndActiveTrue("bob@meet.com")).thenReturn(Optional.of(bob));

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        when(taskScheduler.scheduleAtFixedRate(runnableCaptor.capture(), any(Duration.class)))
                .thenReturn(mock(ScheduledFuture.class));

        monitor.startSessionPolling(event);
        // Run two consecutive poll ticks while still inactive
        runnableCaptor.getValue().run();
        runnableCaptor.getValue().run();

        // Initial snapshot + 2 ticks = 3 notify calls (dedup enforced inside NotificationService, which is mocked here)
        verify(notificationService, times(3)).notify(eq(NotificationType.MEETING_NOT_STARTED_15), eq(event), isNull());
        verify(meetClient, never()).getActiveParticipants(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void pollingTick_proceedsWithAttendanceTrackingOnceMeetingBecomesActive() throws Exception {
        // Meeting not active at class start, then becomes active on first poll tick
        when(meetClient.isMeetingActive("abc-def"))
                .thenReturn(false)    // initial snapshot
                .thenReturn(true);    // first poll tick
        when(meetClient.getActiveParticipants("abc-def")).thenReturn(List.of(ALICE_PARTICIPANT));
        when(studentRepository.findByMeetEmailAndActiveTrue("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmailAndActiveTrue("bob@meet.com")).thenReturn(Optional.of(bob));
        when(studentRepository.findByGoogleUserIdAndActiveTrue("uid-alice")).thenReturn(Optional.of(alice));
        when(attendanceRepository.findByStudentIdAndCalendarEventIdAndDate(anyLong(), anyString(), any()))
                .thenReturn(Optional.empty());

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        when(taskScheduler.scheduleAtFixedRate(runnableCaptor.capture(), any(Duration.class)))
                .thenReturn(mock(ScheduledFuture.class));

        monitor.startSessionPolling(event);
        // Reminder sent for initial inactive state
        verify(notificationService).notify(eq(NotificationType.MEETING_NOT_STARTED_15), eq(event), isNull());

        // First poll: meeting is now active — Alice is tracked, no more reminder
        runnableCaptor.getValue().run();

        // No more MEETING_NOT_STARTED_15 after meeting started
        verify(notificationService, times(1)).notify(eq(NotificationType.MEETING_NOT_STARTED_15), eq(event), isNull());
        // Alice recorded (as LATE since lateBufferMinutes=5 and test runs instantly past threshold)
        verify(notificationService).notify(any(), eq(event), eq(new StudentRecipient(alice)));
    }

    // --- finalizeSession ---

    @Test
    void finalizeSession_marksAbsentAndNotifiesForMissingStudents() throws Exception {
        when(meetClient.getAllParticipants(anyString())).thenReturn(List.of());
        when(studentRepository.findByMeetEmailAndActiveTrue("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmailAndActiveTrue("bob@meet.com")).thenReturn(Optional.of(bob));
        when(attendanceRepository.findByStudentIdAndCalendarEventIdAndDate(anyLong(), anyString(), any()))
                .thenReturn(Optional.empty());

        monitor.finalizeSession(event);

        ArgumentCaptor<Attendance> captor = ArgumentCaptor.forClass(Attendance.class);
        verify(attendanceRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).allMatch(a -> a.getStatus() == AttendanceStatus.ABSENT);
        verify(notificationService).notify(NotificationType.ABSENT, event, new StudentRecipient(alice));
        verify(notificationService).notify(NotificationType.ABSENT, event, new StudentRecipient(bob));
    }

    @Test
    void finalizeSession_doesNotMarkAbsentIfAttendanceAlreadyRecorded() throws Exception {
        when(meetClient.getAllParticipants(anyString())).thenReturn(List.of());
        when(studentRepository.findByMeetEmailAndActiveTrue("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmailAndActiveTrue("bob@meet.com")).thenReturn(Optional.of(bob));
        when(attendanceRepository.findByStudentIdAndCalendarEventIdAndDate(eq(1L), anyString(), any()))
                .thenReturn(Optional.of(Attendance.builder().student(alice)
                        .calendarEventId("evt-1").date(LocalDate.now())
                        .status(AttendanceStatus.PRESENT).build()));
        when(attendanceRepository.findByStudentIdAndCalendarEventIdAndDate(eq(2L), anyString(), any()))
                .thenReturn(Optional.empty());

        monitor.finalizeSession(event);

        verify(notificationService, never()).notify(eq(NotificationType.ABSENT), eq(event), eq(new StudentRecipient(alice)));
        verify(notificationService).notify(NotificationType.ABSENT, event, new StudentRecipient(bob));
    }

    // --- getUpcomingChecks ---

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
        monitor.scheduleEventsForToday(); // first sync — futures stored

        when(taskScheduler.schedule(any(Runnable.class), any(java.time.Instant.class)))
                .thenReturn(mock(ScheduledFuture.class));
        monitor.scheduleEventsForToday(); // re-sync — old futures must be cancelled

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
        monitor.scheduleEventsForToday(); // schedule the event

        when(calendarSyncService.getTodaysEvents()).thenReturn(List.of()); // event removed
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
        monitor.scheduleEventsForToday(); // record initial start time

        CalendarEvent rescheduled = new CalendarEvent("evt-rescheduled", "Math Class",
                "https://meet.google.com/xyz", "xyz",
                LocalDateTime.now().plusHours(3), LocalDateTime.now().plusHours(4),
                List.of());
        when(calendarSyncService.getTodaysEvents()).thenReturn(List.of(rescheduled));
        monitor.scheduleEventsForToday(); // same event ID, different start time

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
        monitor.scheduleEventsForToday(); // same start time — no log clear

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
        monitor.scheduleEventsForToday(); // initial schedule — T-15 and T-3 both far future

        CalendarEvent moved = new CalendarEvent("evt-moved", "Math Class",
                "https://meet.google.com/abc", "abc",
                LocalDateTime.now().plusMinutes(4), LocalDateTime.now().plusMinutes(64),
                List.of());
        when(calendarSyncService.getTodaysEvents()).thenReturn(List.of(moved));
        monitor.scheduleEventsForToday(); // re-sync after reschedule

        List<MeetAttendanceMonitor.ScheduledCheck> checks = monitor.getUpcomingChecks();
        List<String> types = checks.stream().map(MeetAttendanceMonitor.ScheduledCheck::checkType).toList();
        assertThat(types).doesNotContain("MEETING_NOT_STARTED_15");
        assertThat(types).contains("PRE_CLASS_JOINS");
        // Notification logs must have been cleared so the T-3 notification can re-fire
        verify(notificationService).clearTodayLogsForEvent("evt-moved");
    }

    // --- checkPreClassJoins: teacher path ---

    @Test
    void checkPreClassJoins_notifiesTeacherWhenNotYetPresent() throws Exception {
        CalendarEvent teacherEvent = new CalendarEvent("evt-t", "Math Class",
                "https://meet.google.com/abc-def", "abc-def",
                LocalDateTime.now().plusMinutes(3), LocalDateTime.now().plusHours(1),
                List.of("alice@meet.com", "carol@meet.com"));
        when(meetClient.getActiveParticipants("abc-def")).thenReturn(List.of());
        when(studentRepository.findByMeetEmailAndActiveTrue("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmailAndActiveTrue("carol@meet.com")).thenReturn(Optional.empty());
        when(teacherRepository.findByMeetEmailAndActiveTrue("alice@meet.com")).thenReturn(Optional.empty());
        when(teacherRepository.findByMeetEmailAndActiveTrue("carol@meet.com")).thenReturn(Optional.of(carol));

        monitor.checkPreClassJoins(teacherEvent);

        verify(notificationService).notify(NotificationType.NOT_YET_JOINED_3, teacherEvent, new TeacherRecipient(carol));
    }

    @Test
    void checkPreClassJoins_doesNotNotifyTeacherAlreadyPresent() throws Exception {
        CalendarEvent teacherEvent = new CalendarEvent("evt-t", "Math Class",
                "https://meet.google.com/abc-def", "abc-def",
                LocalDateTime.now().plusMinutes(3), LocalDateTime.now().plusHours(1),
                List.of("alice@meet.com", "carol@meet.com"));
        when(meetClient.getActiveParticipants("abc-def")).thenReturn(List.of(CAROL_PARTICIPANT));
        when(studentRepository.findByMeetEmailAndActiveTrue("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmailAndActiveTrue("carol@meet.com")).thenReturn(Optional.empty());
        when(teacherRepository.findByMeetEmailAndActiveTrue("alice@meet.com")).thenReturn(Optional.empty());
        when(teacherRepository.findByMeetEmailAndActiveTrue("carol@meet.com")).thenReturn(Optional.of(carol));
        when(teacherRepository.findByGoogleUserIdAndActiveTrue("uid-carol")).thenReturn(Optional.of(carol));

        monitor.checkPreClassJoins(teacherEvent);

        verify(notificationService, never()).notify(eq(NotificationType.NOT_YET_JOINED_3), eq(teacherEvent), eq(new TeacherRecipient(carol)));
    }

    @Test
    void checkPreClassJoins_notifiesMissingStudentButNotPresentTeacher() throws Exception {
        CalendarEvent teacherEvent = new CalendarEvent("evt-t", "Math Class",
                "https://meet.google.com/abc-def", "abc-def",
                LocalDateTime.now().plusMinutes(3), LocalDateTime.now().plusHours(1),
                List.of("alice@meet.com", "bob@meet.com", "carol@meet.com"));
        when(meetClient.getActiveParticipants("abc-def")).thenReturn(List.of(CAROL_PARTICIPANT, ALICE_PARTICIPANT));
        when(studentRepository.findByMeetEmailAndActiveTrue("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmailAndActiveTrue("bob@meet.com")).thenReturn(Optional.of(bob));
        when(studentRepository.findByMeetEmailAndActiveTrue("carol@meet.com")).thenReturn(Optional.empty());
        when(teacherRepository.findByMeetEmailAndActiveTrue("alice@meet.com")).thenReturn(Optional.empty());
        when(teacherRepository.findByMeetEmailAndActiveTrue("bob@meet.com")).thenReturn(Optional.empty());
        when(teacherRepository.findByMeetEmailAndActiveTrue("carol@meet.com")).thenReturn(Optional.of(carol));
        when(studentRepository.findByGoogleUserIdAndActiveTrue("uid-alice")).thenReturn(Optional.of(alice));
        when(studentRepository.findByGoogleUserIdAndActiveTrue("uid-carol")).thenReturn(Optional.empty());
        when(teacherRepository.findByGoogleUserIdAndActiveTrue("uid-carol")).thenReturn(Optional.of(carol));

        monitor.checkPreClassJoins(teacherEvent);

        verify(notificationService).notify(NotificationType.NOT_YET_JOINED_3, teacherEvent, new StudentRecipient(bob));
        verify(notificationService, never()).notify(eq(NotificationType.NOT_YET_JOINED_3), eq(teacherEvent), eq(new StudentRecipient(alice)));
        verify(notificationService, never()).notify(eq(NotificationType.NOT_YET_JOINED_3), eq(teacherEvent), eq(new TeacherRecipient(carol)));
    }

    // --- finalizeSession: teacher path ---

    @Test
    void finalizeSession_marksTeacherAbsentWhenNotInParticipantHistory() throws Exception {
        CalendarEvent teacherEvent = new CalendarEvent("evt-t", "Math Class",
                "https://meet.google.com/abc-def", "abc-def",
                LocalDateTime.now().minusHours(1), LocalDateTime.now(),
                List.of("carol@meet.com"));
        when(meetClient.getAllParticipants("abc-def")).thenReturn(List.of());
        when(teacherRepository.findByMeetEmailAndActiveTrue("carol@meet.com")).thenReturn(Optional.of(carol));
        when(attendanceRepository.findByTeacherIdAndCalendarEventIdAndDate(eq(10L), anyString(), any()))
                .thenReturn(Optional.empty());

        monitor.finalizeSession(teacherEvent);

        ArgumentCaptor<Attendance> captor = ArgumentCaptor.forClass(Attendance.class);
        verify(attendanceRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(AttendanceStatus.ABSENT);
        verify(notificationService).notify(NotificationType.TEACHER_ABSENT, teacherEvent, new TeacherRecipient(carol));
    }

    @Test
    void finalizeSession_marksTeacherLateWhenJoinTimeAfterThreshold() throws Exception {
        CalendarEvent teacherEvent = new CalendarEvent("evt-t", "Math Class",
                "https://meet.google.com/abc-def", "abc-def",
                LocalDateTime.now().minusHours(1), LocalDateTime.now(),
                List.of("carol@meet.com"));
        // Carol joined well after the late threshold (class started 1h ago, buffer=5min → late threshold 55min ago)
        MeetParticipant carolLate = new MeetParticipant("uid-carol", "Carol",
                java.time.Instant.now().minusSeconds(10)); // joined 10 seconds ago = very late
        when(meetClient.getAllParticipants("abc-def")).thenReturn(List.of(carolLate));
        when(teacherRepository.findByMeetEmailAndActiveTrue("carol@meet.com")).thenReturn(Optional.of(carol));
        carol.setGoogleUserId("uid-carol");
        when(attendanceRepository.findByTeacherIdAndCalendarEventIdAndDate(eq(10L), anyString(), any()))
                .thenReturn(Optional.empty());

        monitor.finalizeSession(teacherEvent);

        ArgumentCaptor<Attendance> captor = ArgumentCaptor.forClass(Attendance.class);
        verify(attendanceRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(AttendanceStatus.LATE);
        verify(notificationService).notify(NotificationType.TEACHER_LATE, teacherEvent, new TeacherRecipient(carol));
    }

    @Test
    void finalizeSession_marksTeacherPresentWhenJoinTimeOnTime() throws Exception {
        CalendarEvent teacherEvent = new CalendarEvent("evt-t", "Math Class",
                "https://meet.google.com/abc-def", "abc-def",
                LocalDateTime.now().minusHours(1), LocalDateTime.now(),
                List.of("carol@meet.com"));
        // Carol joined right at class start (1 hour ago), well within the 5-minute late buffer
        MeetParticipant carolOnTime = new MeetParticipant("uid-carol", "Carol",
                java.time.Instant.now().minusSeconds(3600)); // joined exactly at start
        when(meetClient.getAllParticipants("abc-def")).thenReturn(List.of(carolOnTime));
        when(teacherRepository.findByMeetEmailAndActiveTrue("carol@meet.com")).thenReturn(Optional.of(carol));
        carol.setGoogleUserId("uid-carol");
        when(attendanceRepository.findByTeacherIdAndCalendarEventIdAndDate(eq(10L), anyString(), any()))
                .thenReturn(Optional.empty());

        monitor.finalizeSession(teacherEvent);

        ArgumentCaptor<Attendance> captor = ArgumentCaptor.forClass(Attendance.class);
        verify(attendanceRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(AttendanceStatus.PRESENT);
        verify(notificationService).notify(NotificationType.TEACHER_ARRIVED, teacherEvent, new TeacherRecipient(carol));
    }

    @Test
    void finalizeSession_doesNotDuplicateTeacherAttendanceIfAlreadyRecorded() throws Exception {
        CalendarEvent teacherEvent = new CalendarEvent("evt-t", "Math Class",
                "https://meet.google.com/abc-def", "abc-def",
                LocalDateTime.now().minusHours(1), LocalDateTime.now(),
                List.of("carol@meet.com"));
        when(meetClient.getAllParticipants("abc-def")).thenReturn(List.of());
        when(teacherRepository.findByMeetEmailAndActiveTrue("carol@meet.com")).thenReturn(Optional.of(carol));
        when(attendanceRepository.findByTeacherIdAndCalendarEventIdAndDate(eq(10L), anyString(), any()))
                .thenReturn(Optional.of(Attendance.builder().teacher(carol)
                        .calendarEventId("evt-t").date(LocalDate.now())
                        .status(AttendanceStatus.PRESENT).build()));

        monitor.finalizeSession(teacherEvent);

        verify(attendanceRepository, never()).save(argThat(a -> a.getTeacher() != null && a.getTeacher().equals(carol)));
        verify(notificationService, never()).notify(any(), eq(teacherEvent), eq(new TeacherRecipient(carol)));
    }

    // --- helper ---

    private static <T> T argThat(org.mockito.ArgumentMatcher<T> matcher) {
        return org.mockito.Mockito.argThat(matcher);
    }

    // --- resumeSessionPolling ---

    @Test
    @SuppressWarnings("unchecked")
    void onStartup_callsResumeSessionPollingForInProgressEvent() throws Exception {
        // Event started 10 minutes ago, ends in 50 minutes → in progress
        CalendarEvent inProgress = new CalendarEvent("evt-inprogress", "In-Progress Class",
                "https://meet.google.com/abc-def", "abc-def",
                LocalDateTime.now().minusMinutes(10), LocalDateTime.now().plusMinutes(50),
                List.of("alice@meet.com", "bob@meet.com"));
        when(calendarSyncService.getTodaysEvents()).thenReturn(List.of(inProgress));
        when(taskScheduler.schedule(any(Runnable.class), any(java.time.Instant.class)))
                .thenReturn(mock(ScheduledFuture.class));

        ScheduledFuture pollingFuture = mock(ScheduledFuture.class);
        when(taskScheduler.scheduleAtFixedRate(any(Runnable.class), any(Duration.class)))
                .thenReturn(pollingFuture);
        when(studentRepository.findByMeetEmailAndActiveTrue("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmailAndActiveTrue("bob@meet.com")).thenReturn(Optional.of(bob));
        when(attendanceRepository.findByCalendarEventIdAndDate(eq("evt-inprogress"), any(LocalDate.class)))
                .thenReturn(List.of());
        when(meetClient.getActiveParticipants("abc-def")).thenReturn(List.of());

        monitor.onStartup();

        // Polling future must be registered (resumeSessionPolling was called)
        verify(taskScheduler).scheduleAtFixedRate(any(Runnable.class), any(Duration.class));
        // SESSION_FINALIZE must still be scheduled for the end time
        List<MeetAttendanceMonitor.ScheduledCheck> checks = monitor.getUpcomingChecks();
        assertThat(checks).anyMatch(c -> c.eventId().equals("evt-inprogress") && c.checkType().equals("SESSION_FINALIZE"));
        // SESSION_START must NOT be scheduled (start is in the past)
        assertThat(checks).noneMatch(c -> c.eventId().equals("evt-inprogress") && c.checkType().equals("SESSION_START"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void scheduleEventsForToday_doesNotCallResumeSessionPollingForInProgressEventOnResync() throws Exception {
        // Event started 10 minutes ago, ends in 50 minutes → in progress
        CalendarEvent inProgress = new CalendarEvent("evt-inprogress", "In-Progress Class",
                "https://meet.google.com/abc-def", "abc-def",
                LocalDateTime.now().minusMinutes(10), LocalDateTime.now().plusMinutes(50),
                List.of("alice@meet.com", "bob@meet.com"));
        when(calendarSyncService.getTodaysEvents()).thenReturn(List.of(inProgress));
        when(taskScheduler.schedule(any(Runnable.class), any(java.time.Instant.class)))
                .thenReturn(mock(ScheduledFuture.class));

        // Call scheduleEventsForToday() directly (simulates a manual sync, not startup)
        monitor.scheduleEventsForToday();

        // resumeSessionPolling must NOT be called on a manual re-sync
        verify(taskScheduler, never()).scheduleAtFixedRate(any(Runnable.class), any(Duration.class));
        // SESSION_FINALIZE must still be scheduled
        List<MeetAttendanceMonitor.ScheduledCheck> checks = monitor.getUpcomingChecks();
        assertThat(checks).anyMatch(c -> c.eventId().equals("evt-inprogress") && c.checkType().equals("SESSION_FINALIZE"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void scheduleEventsForToday_doesNotCallResumeSessionPollingForAlreadyEndedEvent() throws Exception {
        // Event that ended 5 minutes ago → should NOT trigger resumeSessionPolling
        CalendarEvent ended = new CalendarEvent("evt-ended", "Ended Class",
                "https://meet.google.com/abc-def", "abc-def",
                LocalDateTime.now().minusMinutes(65), LocalDateTime.now().minusMinutes(5),
                List.of("alice@meet.com"));
        when(calendarSyncService.getTodaysEvents()).thenReturn(List.of(ended));

        monitor.scheduleEventsForToday();

        // No polling future should be registered
        verify(taskScheduler, never()).scheduleAtFixedRate(any(Runnable.class), any(Duration.class));
        // No one-time futures either (all check times in the past)
        verify(taskScheduler, never()).schedule(any(Runnable.class), any(java.time.Instant.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void resumeSessionPolling_preSeedsFromExistingAttendanceRecords() throws Exception {
        // Alice's attendance already recorded before restart; Bob not yet recorded
        Attendance aliceAttendance = Attendance.builder().student(alice)
                .calendarEventId("evt-1").date(LocalDate.now())
                .status(AttendanceStatus.PRESENT).build();
        when(attendanceRepository.findByCalendarEventIdAndDate(eq("evt-1"), any(LocalDate.class)))
                .thenReturn(List.of(aliceAttendance));
        when(studentRepository.findByMeetEmailAndActiveTrue("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmailAndActiveTrue("bob@meet.com")).thenReturn(Optional.of(bob));
        // Snapshot shows both in the meeting
        when(meetClient.getActiveParticipants("abc-def")).thenReturn(List.of(ALICE_PARTICIPANT, BOB_PARTICIPANT));
        when(studentRepository.findByGoogleUserIdAndActiveTrue("uid-alice")).thenReturn(Optional.of(alice));
        when(studentRepository.findByGoogleUserIdAndActiveTrue("uid-bob")).thenReturn(Optional.of(bob));
        when(attendanceRepository.findByStudentIdAndCalendarEventIdAndDate(anyLong(), anyString(), any()))
                .thenReturn(Optional.empty());
        // Note: both students are accounted for after the snapshot (alice pre-seeded, bob
        // in snapshot = totalExpected), so the early-exit path is taken and scheduleAtFixedRate
        // is not called.

        monitor.resumeSessionPolling(event);

        // Alice was pre-seeded → no new save for Alice
        verify(attendanceRepository, never()).save(argThat(a -> a.getStudent() != null && a.getStudent().equals(alice)));
        // Bob was NOT pre-seeded → attendance recorded
        verify(attendanceRepository).save(argThat(a -> a.getStudent() != null && a.getStudent().equals(bob)));
        // All present after snapshot → no periodic polling scheduled
        verify(taskScheduler, never()).scheduleAtFixedRate(any(Runnable.class), any(Duration.class));
    }
}
