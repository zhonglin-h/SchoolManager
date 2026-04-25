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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MeetSessionHandlerTest {

    @Mock StudentRepository studentRepository;
    @Mock TeacherRepository teacherRepository;
    @Mock AttendanceRepository attendanceRepository;
    @Mock NotificationService notificationService;
    @Mock MeetClient meetClient;
    @Mock ThreadPoolTaskScheduler taskScheduler;
    @Mock UpcomingChecksRegistry upcomingChecksRegistry;

    // Construct both with the same mocked dependencies so processParticipants works end-to-end
    private MeetAttendanceHelper attendanceHelper;
    private MeetSessionHandler sessionHandler;

    private CalendarEvent event;
    private Student alice;
    private Student bob;
    private Teacher carol;

    private static final MeetParticipant ALICE_PARTICIPANT = new MeetParticipant("uid-alice", "Alice", null);
    private static final MeetParticipant BOB_PARTICIPANT   = new MeetParticipant("uid-bob",   "Bob",   null);
    private static final MeetParticipant CAROL_PARTICIPANT = new MeetParticipant("uid-carol", "Carol", null);
    private static final String PRINCIPAL_EMAIL = "principal@test.com";

    @BeforeEach
    void setUp() throws Exception {
        attendanceHelper = new MeetAttendanceHelper(studentRepository, teacherRepository,
                attendanceRepository, notificationService);
        sessionHandler = new MeetSessionHandler(attendanceHelper, notificationService,
                meetClient, taskScheduler, attendanceRepository, upcomingChecksRegistry);
        ReflectionTestUtils.setField(sessionHandler, "lateBufferMinutes", 5);

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

        sessionHandler.checkMeetingStarted(event, NotificationType.MEETING_NOT_STARTED_15);

        verify(notificationService).notify(NotificationType.MEETING_NOT_STARTED_15, event, null);
    }

    @Test
    void checkMeetingStarted_doesNothingWhenMeetingIsActive() throws Exception {
        when(meetClient.isMeetingActive("abc-def")).thenReturn(true);

        sessionHandler.checkMeetingStarted(event, NotificationType.MEETING_NOT_STARTED_15);

        verify(notificationService, never()).notify(any(), any(), any());
    }

    @Test
    void checkMeetingStarted_swallowsExceptionGracefully() throws Exception {
        when(meetClient.isMeetingActive(anyString())).thenThrow(new RuntimeException("API error"));

        sessionHandler.checkMeetingStarted(event, NotificationType.MEETING_NOT_STARTED_15);

        verify(notificationService, never()).notify(any(), any(), any());
    }

    // --- checkPreClassJoins ---

    @Test
    void checkPreClassJoins_matchesByGoogleUserId() throws Exception {
        when(meetClient.getActiveParticipants("abc-def")).thenReturn(List.of(ALICE_PARTICIPANT));
        when(studentRepository.findByMeetEmailAndActiveTrue("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmailAndActiveTrue("bob@meet.com")).thenReturn(Optional.of(bob));
        when(studentRepository.findByGoogleUserIdAndActiveTrue("uid-alice")).thenReturn(Optional.of(alice));

        sessionHandler.checkPreClassJoins(event);

        verify(notificationService, never()).notify(eq(NotificationType.NOT_YET_JOINED), eq(event), eq(new StudentSubject(alice)));
        verify(notificationService).notify(NotificationType.NOT_YET_JOINED, event, new StudentSubject(bob));
    }

    @Test
    void checkPreClassJoins_fallsBackToDisplayNameWhenNoGoogleUserId() throws Exception {
        MeetParticipant noId = new MeetParticipant(null, "Alice", null);
        when(meetClient.getActiveParticipants("abc-def")).thenReturn(List.of(noId));
        when(studentRepository.findByMeetEmailAndActiveTrue("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmailAndActiveTrue("bob@meet.com")).thenReturn(Optional.of(bob));
        when(studentRepository.findByNameIgnoreCaseAndActiveTrue("Alice")).thenReturn(Optional.of(alice));

        sessionHandler.checkPreClassJoins(event);

        verify(notificationService, never()).notify(eq(NotificationType.NOT_YET_JOINED), eq(event), eq(new StudentSubject(alice)));
        verify(notificationService).notify(NotificationType.NOT_YET_JOINED, event, new StudentSubject(bob));
    }

    @Test
    void checkPreClassJoins_autoSavesGoogleUserIdOnNameMatch() throws Exception {
        MeetParticipant newParticipant = new MeetParticipant("uid-alice", "Alice", null);
        when(meetClient.getActiveParticipants("abc-def")).thenReturn(List.of(newParticipant));
        when(studentRepository.findByMeetEmailAndActiveTrue("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmailAndActiveTrue("bob@meet.com")).thenReturn(Optional.of(bob));
        when(studentRepository.findByGoogleUserIdAndActiveTrue("uid-alice")).thenReturn(Optional.empty());
        when(studentRepository.findByNameIgnoreCaseAndActiveTrue("Alice")).thenReturn(Optional.of(alice));

        sessionHandler.checkPreClassJoins(event);

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

        sessionHandler.checkPreClassJoins(event);

        verify(notificationService, never()).notify(eq(NotificationType.NOT_YET_JOINED), any(), any());
    }

    @Test
    void checkPreClassJoins_skipsAttendeesNotInStudentRegistry() throws Exception {
        when(meetClient.getActiveParticipants("abc-def")).thenReturn(List.of());
        when(studentRepository.findByMeetEmailAndActiveTrue("alice@meet.com")).thenReturn(Optional.empty());
        when(studentRepository.findByMeetEmailAndActiveTrue("bob@meet.com")).thenReturn(Optional.of(bob));

        sessionHandler.checkPreClassJoins(event);

        verify(notificationService).notify(NotificationType.NOT_YET_JOINED, event, new StudentSubject(bob));
        verify(notificationService, never()).notify(eq(NotificationType.NOT_YET_JOINED), eq(event), eq(new StudentSubject(alice)));
    }

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

        sessionHandler.checkPreClassJoins(teacherEvent);

        verify(notificationService).notify(NotificationType.NOT_YET_JOINED, teacherEvent, new TeacherSubject(carol));
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

        sessionHandler.checkPreClassJoins(teacherEvent);

        verify(notificationService, never()).notify(eq(NotificationType.NOT_YET_JOINED), eq(teacherEvent), eq(new TeacherSubject(carol)));
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

        sessionHandler.checkPreClassJoins(teacherEvent);

        verify(notificationService).notify(NotificationType.NOT_YET_JOINED, teacherEvent, new StudentSubject(bob));
        verify(notificationService, never()).notify(eq(NotificationType.NOT_YET_JOINED), eq(teacherEvent), eq(new StudentSubject(alice)));
        verify(notificationService, never()).notify(eq(NotificationType.NOT_YET_JOINED), eq(teacherEvent), eq(new TeacherSubject(carol)));
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

        sessionHandler.startSessionPolling(event);

        ArgumentCaptor<Attendance> captor = ArgumentCaptor.forClass(Attendance.class);
        verify(attendanceRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).allMatch(a -> a.getStatus() == AttendanceStatus.PRESENT);
        verify(taskScheduler, never()).scheduleAtFixedRate(any(Runnable.class), any(Duration.class));
        verify(upcomingChecksRegistry).removePollingEntry(event.getId());
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

        sessionHandler.startSessionPolling(event);

        verify(attendanceRepository, never()).save(argThat(a -> a.getStudent().equals(alice)));
        verify(taskScheduler).scheduleAtFixedRate(any(Runnable.class), any(Duration.class));
    }

    // --- startSessionPolling (polling tick) ---

    @Test
    @SuppressWarnings("unchecked")
    void pollingTick_marksLateAndNotifiesForDelayedJoiner() throws Exception {
        ReflectionTestUtils.setField(sessionHandler, "lateBufferMinutes", 0);
        when(meetClient.isMeetingActive("abc-def")).thenReturn(true);
        when(meetClient.getActiveParticipants("abc-def"))
                .thenReturn(List.of())
                .thenReturn(List.of(ALICE_PARTICIPANT));
        when(studentRepository.findByMeetEmailAndActiveTrue("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmailAndActiveTrue("bob@meet.com")).thenReturn(Optional.of(bob));
        when(studentRepository.findByGoogleUserIdAndActiveTrue("uid-alice")).thenReturn(Optional.of(alice));
        when(attendanceRepository.findByStudentIdAndCalendarEventIdAndDate(anyLong(), anyString(), any()))
                .thenReturn(Optional.empty());

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        when(taskScheduler.scheduleAtFixedRate(runnableCaptor.capture(), any(Duration.class)))
                .thenReturn(mock(ScheduledFuture.class));

        sessionHandler.startSessionPolling(event);
        runnableCaptor.getValue().run();

        ArgumentCaptor<Attendance> attendanceCaptor = ArgumentCaptor.forClass(Attendance.class);
        verify(attendanceRepository, atLeastOnce()).save(attendanceCaptor.capture());
        assertThat(attendanceCaptor.getAllValues())
                .anyMatch(a -> a.getStudent().equals(alice) && a.getStatus() == AttendanceStatus.LATE);
        verify(notificationService).notify(NotificationType.LATE, event, new StudentSubject(alice));
    }

    @Test
    @SuppressWarnings("unchecked")
    void pollingTick_notifiesAllPresentWhenEveryoneAccountedFor() throws Exception {
        when(meetClient.isMeetingActive("abc-def")).thenReturn(true);
        when(meetClient.getActiveParticipants("abc-def")).thenReturn(List.of(ALICE_PARTICIPANT, BOB_PARTICIPANT));
        when(studentRepository.findByMeetEmailAndActiveTrue("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmailAndActiveTrue("bob@meet.com")).thenReturn(Optional.of(bob));
        when(studentRepository.findByGoogleUserIdAndActiveTrue("uid-alice")).thenReturn(Optional.of(alice));
        when(studentRepository.findByGoogleUserIdAndActiveTrue("uid-bob")).thenReturn(Optional.of(bob));
        when(attendanceRepository.findByStudentIdAndCalendarEventIdAndDate(anyLong(), anyString(), any()))
                .thenReturn(Optional.empty());

        sessionHandler.startSessionPolling(event);

        verify(notificationService, never()).notify(NotificationType.ALL_PRESENT, event, null);
        verify(taskScheduler, never()).scheduleAtFixedRate(any(Runnable.class), any(Duration.class));
        verify(upcomingChecksRegistry).removePollingEntry(event.getId());
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

        sessionHandler.startSessionPolling(event);

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

        sessionHandler.startSessionPolling(event);
        runnableCaptor.getValue().run();
        runnableCaptor.getValue().run();

        verify(notificationService, times(3)).notify(eq(NotificationType.MEETING_NOT_STARTED_15), eq(event), isNull());
        verify(meetClient, never()).getActiveParticipants(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void pollingTick_proceedsWithAttendanceTrackingOnceMeetingBecomesActive() throws Exception {
        when(meetClient.isMeetingActive("abc-def"))
                .thenReturn(false)
                .thenReturn(true);
        when(meetClient.getActiveParticipants("abc-def")).thenReturn(List.of(ALICE_PARTICIPANT));
        when(studentRepository.findByMeetEmailAndActiveTrue("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmailAndActiveTrue("bob@meet.com")).thenReturn(Optional.of(bob));
        when(studentRepository.findByGoogleUserIdAndActiveTrue("uid-alice")).thenReturn(Optional.of(alice));
        when(attendanceRepository.findByStudentIdAndCalendarEventIdAndDate(anyLong(), anyString(), any()))
                .thenReturn(Optional.empty());

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        when(taskScheduler.scheduleAtFixedRate(runnableCaptor.capture(), any(Duration.class)))
                .thenReturn(mock(ScheduledFuture.class));

        sessionHandler.startSessionPolling(event);
        verify(notificationService).notify(eq(NotificationType.MEETING_NOT_STARTED_15), eq(event), isNull());

        runnableCaptor.getValue().run();

        verify(notificationService, times(1)).notify(eq(NotificationType.MEETING_NOT_STARTED_15), eq(event), isNull());
        verify(notificationService).notify(any(), eq(event), eq(new StudentSubject(alice)));
    }

    // --- finalizeSession ---

    @Test
    void finalizeSession_marksAbsentAndNotifiesForMissingStudents() throws Exception {
        when(meetClient.getAllParticipants(anyString())).thenReturn(List.of());
        when(studentRepository.findByMeetEmailAndActiveTrue("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmailAndActiveTrue("bob@meet.com")).thenReturn(Optional.of(bob));
        when(attendanceRepository.findByStudentIdAndCalendarEventIdAndDate(anyLong(), anyString(), any()))
                .thenReturn(Optional.empty());

        sessionHandler.finalizeSession(event);

        ArgumentCaptor<Attendance> captor = ArgumentCaptor.forClass(Attendance.class);
        verify(attendanceRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).allMatch(a -> a.getStatus() == AttendanceStatus.ABSENT);
        verify(notificationService).notify(NotificationType.ABSENT, event, new StudentSubject(alice));
        verify(notificationService).notify(NotificationType.ABSENT, event, new StudentSubject(bob));
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

        sessionHandler.finalizeSession(event);

        verify(notificationService, never()).notify(eq(NotificationType.ABSENT), eq(event), eq(new StudentSubject(alice)));
        verify(notificationService).notify(NotificationType.ABSENT, event, new StudentSubject(bob));
    }

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

        sessionHandler.finalizeSession(teacherEvent);

        ArgumentCaptor<Attendance> captor = ArgumentCaptor.forClass(Attendance.class);
        verify(attendanceRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(AttendanceStatus.ABSENT);
        verify(notificationService).notify(NotificationType.TEACHER_ABSENT, teacherEvent, new TeacherSubject(carol));
    }

    @Test
    void finalizeSession_marksTeacherLateWhenJoinTimeAfterThreshold() throws Exception {
        CalendarEvent teacherEvent = new CalendarEvent("evt-t", "Math Class",
                "https://meet.google.com/abc-def", "abc-def",
                LocalDateTime.now().minusHours(1), LocalDateTime.now(),
                List.of("carol@meet.com"));
        MeetParticipant carolLate = new MeetParticipant("uid-carol", "Carol",
                java.time.Instant.now().minusSeconds(10));
        when(meetClient.getAllParticipants("abc-def")).thenReturn(List.of(carolLate));
        when(teacherRepository.findByMeetEmailAndActiveTrue("carol@meet.com")).thenReturn(Optional.of(carol));
        carol.setGoogleUserId("uid-carol");
        when(attendanceRepository.findByTeacherIdAndCalendarEventIdAndDate(eq(10L), anyString(), any()))
                .thenReturn(Optional.empty());

        sessionHandler.finalizeSession(teacherEvent);

        ArgumentCaptor<Attendance> captor = ArgumentCaptor.forClass(Attendance.class);
        verify(attendanceRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(AttendanceStatus.LATE);
        verify(notificationService).notify(NotificationType.TEACHER_LATE, teacherEvent, new TeacherSubject(carol));
    }

    @Test
    void finalizeSession_marksTeacherPresentWhenJoinTimeOnTime() throws Exception {
        CalendarEvent teacherEvent = new CalendarEvent("evt-t", "Math Class",
                "https://meet.google.com/abc-def", "abc-def",
                LocalDateTime.now().minusHours(1), LocalDateTime.now(),
                List.of("carol@meet.com"));
        MeetParticipant carolOnTime = new MeetParticipant("uid-carol", "Carol",
                java.time.Instant.now().minusSeconds(3600));
        when(meetClient.getAllParticipants("abc-def")).thenReturn(List.of(carolOnTime));
        when(teacherRepository.findByMeetEmailAndActiveTrue("carol@meet.com")).thenReturn(Optional.of(carol));
        carol.setGoogleUserId("uid-carol");
        when(attendanceRepository.findByTeacherIdAndCalendarEventIdAndDate(eq(10L), anyString(), any()))
                .thenReturn(Optional.empty());

        sessionHandler.finalizeSession(teacherEvent);

        ArgumentCaptor<Attendance> captor = ArgumentCaptor.forClass(Attendance.class);
        verify(attendanceRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(AttendanceStatus.PRESENT);
        verify(notificationService).notify(NotificationType.TEACHER_ARRIVED, teacherEvent, new TeacherSubject(carol));
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

        sessionHandler.finalizeSession(teacherEvent);

        verify(attendanceRepository, never()).save(argThat(a -> a.getTeacher() != null && a.getTeacher().equals(carol)));
        verify(notificationService, never()).notify(any(), eq(teacherEvent), eq(new TeacherSubject(carol)));
    }

    // --- resumeSessionPolling ---

    @Test
    @SuppressWarnings("unchecked")
    void resumeSessionPolling_preSeedsFromExistingAttendanceRecords() throws Exception {
        Attendance aliceAttendance = Attendance.builder().student(alice)
                .calendarEventId("evt-1").date(LocalDate.now())
                .status(AttendanceStatus.PRESENT).build();
        when(attendanceRepository.findByCalendarEventIdAndDate(eq("evt-1"), any(LocalDate.class)))
                .thenReturn(List.of(aliceAttendance));
        when(studentRepository.findByMeetEmailAndActiveTrue("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmailAndActiveTrue("bob@meet.com")).thenReturn(Optional.of(bob));
        when(meetClient.isMeetingActive("abc-def")).thenReturn(true);
        when(meetClient.getActiveParticipants("abc-def")).thenReturn(List.of(ALICE_PARTICIPANT, BOB_PARTICIPANT));
        when(studentRepository.findByGoogleUserIdAndActiveTrue("uid-alice")).thenReturn(Optional.of(alice));
        when(studentRepository.findByGoogleUserIdAndActiveTrue("uid-bob")).thenReturn(Optional.of(bob));
        when(attendanceRepository.findByStudentIdAndCalendarEventIdAndDate(anyLong(), anyString(), any()))
                .thenReturn(Optional.empty());

        sessionHandler.resumeSessionPolling(event);

        verify(attendanceRepository, never()).save(argThat(a -> a.getStudent() != null && a.getStudent().equals(alice)));
        verify(attendanceRepository).save(argThat(a -> a.getStudent() != null && a.getStudent().equals(bob)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void resumeSessionPolling_sendsReminderWhenMeetingNotActiveAtStartup() throws Exception {
        when(attendanceRepository.findByCalendarEventIdAndDate(eq("evt-1"), any(LocalDate.class)))
                .thenReturn(List.of());
        when(studentRepository.findByMeetEmailAndActiveTrue("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmailAndActiveTrue("bob@meet.com")).thenReturn(Optional.of(bob));
        when(meetClient.isMeetingActive("abc-def")).thenReturn(false);
        when(taskScheduler.scheduleAtFixedRate(any(Runnable.class), any(Duration.class)))
                .thenReturn(mock(ScheduledFuture.class));

        sessionHandler.resumeSessionPolling(event);

        verify(notificationService).notify(eq(NotificationType.MEETING_NOT_STARTED_15), eq(event), isNull());
        verify(meetClient, never()).getActiveParticipants(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void resumeSessionPolling_pollingTickSendsReminderEachTickWhileMeetingInactive() throws Exception {
        when(attendanceRepository.findByCalendarEventIdAndDate(eq("evt-1"), any(LocalDate.class)))
                .thenReturn(List.of());
        when(studentRepository.findByMeetEmailAndActiveTrue("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmailAndActiveTrue("bob@meet.com")).thenReturn(Optional.of(bob));
        when(meetClient.isMeetingActive("abc-def")).thenReturn(false);

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        when(taskScheduler.scheduleAtFixedRate(runnableCaptor.capture(), any(Duration.class)))
                .thenReturn(mock(ScheduledFuture.class));

        sessionHandler.resumeSessionPolling(event);
        runnableCaptor.getValue().run();
        runnableCaptor.getValue().run();

        // initial snapshot + 2 ticks = 3 reminders
        verify(notificationService, times(3)).notify(eq(NotificationType.MEETING_NOT_STARTED_15), eq(event), isNull());
        verify(meetClient, never()).getActiveParticipants(anyString());
    }

    // --- unmatched invitees ---

    @Test
    void checkPreClassJoins_notifiesUnmatchedGuestsForUnknownInvitee() throws Exception {
        // Event has "unknown@meet.com" which is not in student or teacher repository
        CalendarEvent eventWithUnknown = new CalendarEvent("evt-u", "Math Class",
                "https://meet.google.com/abc-def", "abc-def",
                LocalDateTime.now().plusMinutes(3), LocalDateTime.now().plusHours(1),
                List.of("alice@meet.com", "unknown@meet.com"));
        when(meetClient.getActiveParticipants("abc-def")).thenReturn(List.of());
        when(studentRepository.findByMeetEmailAndActiveTrue("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmailAndActiveTrue("unknown@meet.com")).thenReturn(Optional.empty());
        when(teacherRepository.findByMeetEmailAndActiveTrue("alice@meet.com")).thenReturn(Optional.empty());
        when(teacherRepository.findByMeetEmailAndActiveTrue("unknown@meet.com")).thenReturn(Optional.empty());

        sessionHandler.checkPreClassJoins(eventWithUnknown);

        verify(notificationService).notify(
                NotificationType.UNMATCHED_GUESTS,
                eventWithUnknown,
                new GuestSubject(List.of("unknown@meet.com"), List.of()));
    }

    @Test
    void checkPreClassJoins_includesUnmatchedPrincipalInviteeAndParticipantsInMessage() throws Exception {
        CalendarEvent eventWithPrincipal = new CalendarEvent("evt-u2", "Math Class",
                "https://meet.google.com/abc-def", "abc-def",
                LocalDateTime.now().plusMinutes(3), LocalDateTime.now().plusHours(1),
                List.of("alice@meet.com", PRINCIPAL_EMAIL, "unknown@meet.com"));
        MeetParticipant mysteryParticipant = new MeetParticipant(null, "Mystery Person", null);

        when(meetClient.getActiveParticipants("abc-def")).thenReturn(List.of(ALICE_PARTICIPANT, mysteryParticipant));
        when(studentRepository.findByMeetEmailAndActiveTrue("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmailAndActiveTrue(PRINCIPAL_EMAIL)).thenReturn(Optional.empty());
        when(studentRepository.findByMeetEmailAndActiveTrue("unknown@meet.com")).thenReturn(Optional.empty());
        when(studentRepository.findByGoogleUserIdAndActiveTrue("uid-alice")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetDisplayNameIgnoreCaseAndActiveTrue("Mystery Person")).thenReturn(Optional.empty());
        when(studentRepository.findByNameIgnoreCaseAndActiveTrue("Mystery Person")).thenReturn(Optional.empty());
        when(teacherRepository.findByMeetEmailAndActiveTrue("alice@meet.com")).thenReturn(Optional.empty());
        when(teacherRepository.findByMeetEmailAndActiveTrue(PRINCIPAL_EMAIL)).thenReturn(Optional.empty());
        when(teacherRepository.findByMeetEmailAndActiveTrue("unknown@meet.com")).thenReturn(Optional.empty());
        when(teacherRepository.findByMeetDisplayNameIgnoreCaseAndActiveTrue("Mystery Person")).thenReturn(Optional.empty());
        when(teacherRepository.findByNameIgnoreCaseAndActiveTrue("Mystery Person")).thenReturn(Optional.empty());

        sessionHandler.checkPreClassJoins(eventWithPrincipal);

        verify(notificationService).notify(
                eq(NotificationType.UNMATCHED_GUESTS),
                eq(eventWithPrincipal),
                argThat(r -> r instanceof GuestSubject gr
                        && gr.unmatchedInvitees().equals(List.of(PRINCIPAL_EMAIL, "unknown@meet.com"))
                        && gr.unmatchedParticipants().equals(List.of("Mystery Person"))));
    }

    @Test
    void checkPreClassJoins_doesNotNotifyUnmatchedGuestsWhenAllInviteesKnown() throws Exception {
        when(meetClient.getActiveParticipants("abc-def")).thenReturn(List.of());
        when(studentRepository.findByMeetEmailAndActiveTrue("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmailAndActiveTrue("bob@meet.com")).thenReturn(Optional.of(bob));

        sessionHandler.checkPreClassJoins(event);

        verify(notificationService, never()).notify(eq(NotificationType.UNMATCHED_GUESTS), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void pollingTick_notifiesUnmatchedGuestsEachTickForUnknownInvitee() throws Exception {
        CalendarEvent eventWithUnknown = new CalendarEvent("evt-u", "Math Class",
                "https://meet.google.com/abc-def", "abc-def",
                LocalDateTime.now(), LocalDateTime.now().plusHours(1),
                List.of("alice@meet.com", "bob@meet.com", "unknown@meet.com"));
        when(meetClient.isMeetingActive("abc-def")).thenReturn(true);
        when(meetClient.getActiveParticipants("abc-def")).thenReturn(List.of(ALICE_PARTICIPANT));
        when(studentRepository.findByMeetEmailAndActiveTrue("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmailAndActiveTrue("bob@meet.com")).thenReturn(Optional.of(bob));
        when(studentRepository.findByMeetEmailAndActiveTrue("unknown@meet.com")).thenReturn(Optional.empty());
        when(teacherRepository.findByMeetEmailAndActiveTrue("alice@meet.com")).thenReturn(Optional.empty());
        when(teacherRepository.findByMeetEmailAndActiveTrue("bob@meet.com")).thenReturn(Optional.empty());
        when(teacherRepository.findByMeetEmailAndActiveTrue("unknown@meet.com")).thenReturn(Optional.empty());
        when(studentRepository.findByGoogleUserIdAndActiveTrue("uid-alice")).thenReturn(Optional.of(alice));
        when(attendanceRepository.findByStudentIdAndCalendarEventIdAndDate(anyLong(), anyString(), any()))
                .thenReturn(Optional.empty());

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        when(taskScheduler.scheduleAtFixedRate(runnableCaptor.capture(), any(Duration.class)))
                .thenReturn(mock(ScheduledFuture.class));

        sessionHandler.startSessionPolling(eventWithUnknown);
        runnableCaptor.getValue().run();
        runnableCaptor.getValue().run();

        // Fired three times (initial snapshot + 2 ticks) - no dedup
        verify(notificationService, times(3)).notify(
                NotificationType.UNMATCHED_GUESTS,
                eventWithUnknown,
                new GuestSubject(List.of("unknown@meet.com"), List.of()));
    }

    // --- ALL_PRESENT calls cancelPollingFor (removes registry entry) ---

    @Test
    @SuppressWarnings("unchecked")
    void pollingTick_allPresentCallsCancelPollingForWhichRemovesRegistryEntry() throws Exception {
        when(meetClient.isMeetingActive("abc-def")).thenReturn(true);
        when(meetClient.getActiveParticipants("abc-def")).thenReturn(List.of(ALICE_PARTICIPANT, BOB_PARTICIPANT));
        when(studentRepository.findByMeetEmailAndActiveTrue("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmailAndActiveTrue("bob@meet.com")).thenReturn(Optional.of(bob));
        when(studentRepository.findByGoogleUserIdAndActiveTrue("uid-alice")).thenReturn(Optional.of(alice));
        when(studentRepository.findByGoogleUserIdAndActiveTrue("uid-bob")).thenReturn(Optional.of(bob));
        when(attendanceRepository.findByStudentIdAndCalendarEventIdAndDate(anyLong(), anyString(), any()))
                .thenReturn(Optional.empty());

        sessionHandler.startSessionPolling(event);

        verify(notificationService, never()).notify(NotificationType.ALL_PRESENT, event, null);
        verify(taskScheduler, never()).scheduleAtFixedRate(any(Runnable.class), any(Duration.class));
        verify(upcomingChecksRegistry).removePollingEntry(event.getId());
    }

    // --- helper ---

    private static <T> T argThat(org.mockito.ArgumentMatcher<T> matcher) {
        return org.mockito.Mockito.argThat(matcher);
    }
}
