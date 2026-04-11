package com.school.service;

import com.school.entity.Attendance;
import com.school.entity.AttendanceStatus;
import com.school.entity.Student;
import com.school.integration.MeetClient;
import com.school.integration.MeetParticipant;
import com.school.model.CalendarEvent;
import com.school.service.NotificationType;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    private static final MeetParticipant ALICE_PARTICIPANT = new MeetParticipant("uid-alice", "Alice", null);
    private static final MeetParticipant BOB_PARTICIPANT   = new MeetParticipant("uid-bob",   "Bob",   null);

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
        when(studentRepository.findByMeetEmail("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmail("bob@meet.com")).thenReturn(Optional.of(bob));
        when(studentRepository.findByGoogleUserId("uid-alice")).thenReturn(Optional.of(alice));

        monitor.checkPreClassJoins(event);

        verify(notificationService, never()).notify(eq(NotificationType.NOT_YET_JOINED_3), eq(event), eq(alice));
        verify(notificationService).notify(NotificationType.NOT_YET_JOINED_3, event, bob);
    }

    @Test
    void checkPreClassJoins_fallsBackToDisplayNameWhenNoGoogleUserId() throws Exception {
        MeetParticipant noId = new MeetParticipant(null, "Alice", null);
        when(meetClient.getActiveParticipants("abc-def")).thenReturn(List.of(noId));
        when(studentRepository.findByMeetEmail("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmail("bob@meet.com")).thenReturn(Optional.of(bob));
        when(studentRepository.findByNameIgnoreCase("Alice")).thenReturn(Optional.of(alice));

        monitor.checkPreClassJoins(event);

        verify(notificationService, never()).notify(eq(NotificationType.NOT_YET_JOINED_3), eq(event), eq(alice));
        verify(notificationService).notify(NotificationType.NOT_YET_JOINED_3, event, bob);
    }

    @Test
    void checkPreClassJoins_autoSavesGoogleUserIdOnNameMatch() throws Exception {
        // Alice has no googleUserId stored yet; participant arrives with one
        MeetParticipant newParticipant = new MeetParticipant("uid-alice", "Alice", null);
        when(meetClient.getActiveParticipants("abc-def")).thenReturn(List.of(newParticipant));
        when(studentRepository.findByMeetEmail("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmail("bob@meet.com")).thenReturn(Optional.of(bob));
        when(studentRepository.findByGoogleUserId("uid-alice")).thenReturn(Optional.empty());
        when(studentRepository.findByNameIgnoreCase("Alice")).thenReturn(Optional.of(alice));

        monitor.checkPreClassJoins(event);

        ArgumentCaptor<Student> captor = ArgumentCaptor.forClass(Student.class);
        verify(studentRepository).save(captor.capture());
        assertThat(captor.getValue().getGoogleUserId()).isEqualTo("uid-alice");
    }

    @Test
    void checkPreClassJoins_doesNotNotifyWhenAllPresent() throws Exception {
        when(meetClient.getActiveParticipants("abc-def")).thenReturn(List.of(ALICE_PARTICIPANT, BOB_PARTICIPANT));
        when(studentRepository.findByMeetEmail("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmail("bob@meet.com")).thenReturn(Optional.of(bob));
        when(studentRepository.findByGoogleUserId("uid-alice")).thenReturn(Optional.of(alice));
        when(studentRepository.findByGoogleUserId("uid-bob")).thenReturn(Optional.of(bob));

        monitor.checkPreClassJoins(event);

        verify(notificationService, never()).notify(eq(NotificationType.NOT_YET_JOINED_3), any(), any());
    }

    @Test
    void checkPreClassJoins_skipsAttendeesNotInStudentRegistry() throws Exception {
        when(meetClient.getActiveParticipants("abc-def")).thenReturn(List.of());
        when(studentRepository.findByMeetEmail("alice@meet.com")).thenReturn(Optional.empty());
        when(studentRepository.findByMeetEmail("bob@meet.com")).thenReturn(Optional.of(bob));

        monitor.checkPreClassJoins(event);

        verify(notificationService).notify(NotificationType.NOT_YET_JOINED_3, event, bob);
        verify(notificationService, never()).notify(eq(NotificationType.NOT_YET_JOINED_3), eq(event), eq(alice));
    }

    // --- startSessionPolling (initial snapshot) ---

    @Test
    @SuppressWarnings("unchecked")
    void startSessionPolling_recordsPresentForStudentsAlreadyInMeeting() throws Exception {
        when(meetClient.getActiveParticipants("abc-def")).thenReturn(List.of(ALICE_PARTICIPANT, BOB_PARTICIPANT));
        when(studentRepository.findByMeetEmail("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmail("bob@meet.com")).thenReturn(Optional.of(bob));
        when(studentRepository.findByGoogleUserId("uid-alice")).thenReturn(Optional.of(alice));
        when(studentRepository.findByGoogleUserId("uid-bob")).thenReturn(Optional.of(bob));
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
        when(meetClient.getActiveParticipants("abc-def")).thenReturn(List.of(ALICE_PARTICIPANT));
        when(studentRepository.findByMeetEmail("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmail("bob@meet.com")).thenReturn(Optional.of(bob));
        when(studentRepository.findByGoogleUserId("uid-alice")).thenReturn(Optional.of(alice));
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
        when(meetClient.getActiveParticipants("abc-def"))
                .thenReturn(List.of())                                   // initial snapshot: empty
                .thenReturn(List.of(ALICE_PARTICIPANT));                  // first poll: Alice joins
        when(studentRepository.findByMeetEmail("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmail("bob@meet.com")).thenReturn(Optional.of(bob));
        when(studentRepository.findByGoogleUserId("uid-alice")).thenReturn(Optional.of(alice));
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
        verify(notificationService).notify(NotificationType.LATE, event, alice);
    }

    @Test
    @SuppressWarnings("unchecked")
    void pollingTick_notifiesAllPresentWhenEveryoneAccountedFor() throws Exception {
        when(meetClient.getActiveParticipants("abc-def"))
                .thenReturn(List.of(ALICE_PARTICIPANT, BOB_PARTICIPANT));
        when(studentRepository.findByMeetEmail("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmail("bob@meet.com")).thenReturn(Optional.of(bob));
        when(studentRepository.findByGoogleUserId("uid-alice")).thenReturn(Optional.of(alice));
        when(studentRepository.findByGoogleUserId("uid-bob")).thenReturn(Optional.of(bob));
        when(attendanceRepository.findByStudentIdAndCalendarEventIdAndDate(anyLong(), anyString(), any()))
                .thenReturn(Optional.empty());

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        when(taskScheduler.scheduleAtFixedRate(runnableCaptor.capture(), any(Duration.class)))
                .thenReturn(mock(ScheduledFuture.class));

        monitor.startSessionPolling(event);
        runnableCaptor.getValue().run();

        verify(notificationService).notify(NotificationType.ALL_PRESENT, event, null);
    }

    // --- finalizeSession ---

    @Test
    void finalizeSession_marksAbsentAndNotifiesForMissingStudents() throws Exception {
        when(meetClient.getAllParticipants(anyString())).thenReturn(List.of());
        when(studentRepository.findByMeetEmail("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmail("bob@meet.com")).thenReturn(Optional.of(bob));
        when(attendanceRepository.findByStudentIdAndCalendarEventIdAndDate(anyLong(), anyString(), any()))
                .thenReturn(Optional.empty());

        monitor.finalizeSession(event);

        ArgumentCaptor<Attendance> captor = ArgumentCaptor.forClass(Attendance.class);
        verify(attendanceRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).allMatch(a -> a.getStatus() == AttendanceStatus.ABSENT);
        verify(notificationService).notify(NotificationType.ABSENT, event, alice);
        verify(notificationService).notify(NotificationType.ABSENT, event, bob);
    }

    @Test
    void finalizeSession_doesNotMarkAbsentIfAttendanceAlreadyRecorded() throws Exception {
        when(meetClient.getAllParticipants(anyString())).thenReturn(List.of());
        when(studentRepository.findByMeetEmail("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmail("bob@meet.com")).thenReturn(Optional.of(bob));
        when(attendanceRepository.findByStudentIdAndCalendarEventIdAndDate(eq(1L), anyString(), any()))
                .thenReturn(Optional.of(Attendance.builder().student(alice)
                        .calendarEventId("evt-1").date(LocalDate.now())
                        .status(AttendanceStatus.PRESENT).build()));
        when(attendanceRepository.findByStudentIdAndCalendarEventIdAndDate(eq(2L), anyString(), any()))
                .thenReturn(Optional.empty());

        monitor.finalizeSession(event);

        verify(notificationService, never()).notify(eq(NotificationType.ABSENT), eq(event), eq(alice));
        verify(notificationService).notify(NotificationType.ABSENT, event, bob);
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

    // --- helper ---

    private static <T> T argThat(org.mockito.ArgumentMatcher<T> matcher) {
        return org.mockito.Mockito.argThat(matcher);
    }
}
