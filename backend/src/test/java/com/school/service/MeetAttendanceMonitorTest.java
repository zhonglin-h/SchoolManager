package com.school.service;

import com.school.entity.Attendance;
import com.school.entity.AttendanceStatus;
import com.school.entity.Student;
import com.school.integration.MeetClient;
import com.school.model.CalendarEvent;
import com.school.repository.AttendanceRepository;
import com.school.repository.StudentRepository;
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
    @Mock AttendanceRepository attendanceRepository;
    @Mock NotificationService notificationService;
    @Mock MeetClient meetClient;
    @Mock ThreadPoolTaskScheduler taskScheduler;

    @InjectMocks
    MeetAttendanceMonitor monitor;

    private CalendarEvent event;
    private Student alice;
    private Student bob;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ReflectionTestUtils.setField(monitor, "endBufferMinutes", 5);

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

        monitor.checkMeetingStarted(event, "MEETING_NOT_STARTED_15");

        verify(notificationService).notifyMeetingNotStarted(event, "MEETING_NOT_STARTED_15");
    }

    @Test
    void checkMeetingStarted_doesNothingWhenMeetingIsActive() throws Exception {
        when(meetClient.isMeetingActive("abc-def")).thenReturn(true);

        monitor.checkMeetingStarted(event, "MEETING_NOT_STARTED_15");

        verify(notificationService, never()).notifyMeetingNotStarted(any(), anyString());
    }

    @Test
    void checkMeetingStarted_swallowsExceptionGracefully() throws Exception {
        when(meetClient.isMeetingActive(anyString())).thenThrow(new RuntimeException("API error"));

        // should not throw
        monitor.checkMeetingStarted(event, "MEETING_NOT_STARTED_15");

        verify(notificationService, never()).notifyMeetingNotStarted(any(), anyString());
    }

    // --- checkPreClassJoins ---

    @Test
    void checkPreClassJoins_notifiesForStudentsNotYetJoined() throws Exception {
        when(meetClient.getActiveParticipantEmails("abc-def")).thenReturn(List.of("alice@meet.com"));
        when(studentRepository.findByMeetEmail("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmail("bob@meet.com")).thenReturn(Optional.of(bob));

        monitor.checkPreClassJoins(event);

        // Alice is present — no notification for her
        verify(notificationService, never()).notifyNotYetJoined(event, alice);
        // Bob is missing — notify
        verify(notificationService).notifyNotYetJoined(event, bob);
    }

    @Test
    void checkPreClassJoins_doesNotNotifyWhenAllPresent() throws Exception {
        when(meetClient.getActiveParticipantEmails("abc-def"))
                .thenReturn(List.of("alice@meet.com", "bob@meet.com"));
        when(studentRepository.findByMeetEmail("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmail("bob@meet.com")).thenReturn(Optional.of(bob));

        monitor.checkPreClassJoins(event);

        verify(notificationService, never()).notifyNotYetJoined(any(), any());
    }

    @Test
    void checkPreClassJoins_skipsAttendeesNotInStudentRegistry() throws Exception {
        when(meetClient.getActiveParticipantEmails("abc-def")).thenReturn(List.of());
        // alice@meet.com is an attendee but not registered
        when(studentRepository.findByMeetEmail("alice@meet.com")).thenReturn(Optional.empty());
        when(studentRepository.findByMeetEmail("bob@meet.com")).thenReturn(Optional.of(bob));

        monitor.checkPreClassJoins(event);

        verify(notificationService).notifyNotYetJoined(event, bob);
        verify(notificationService, never()).notifyNotYetJoined(eq(event), eq(alice));
    }

    // --- startSessionPolling (initial snapshot) ---

    @Test
    @SuppressWarnings("unchecked")
    void startSessionPolling_recordsPresentForStudentsAlreadyInMeeting() throws Exception {
        when(meetClient.getActiveParticipantEmails("abc-def"))
                .thenReturn(List.of("alice@meet.com", "bob@meet.com"));
        when(studentRepository.findByMeetEmail("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmail("bob@meet.com")).thenReturn(Optional.of(bob));
        when(attendanceRepository.findByStudentIdAndCalendarEventIdAndDate(anyLong(), anyString(), any()))
                .thenReturn(Optional.empty());
        when(taskScheduler.scheduleAtFixedRate(any(Runnable.class), any(Duration.class)))
                .thenReturn(mock(ScheduledFuture.class));

        monitor.startSessionPolling(event);

        ArgumentCaptor<Attendance> captor = ArgumentCaptor.forClass(Attendance.class);
        // should save PRESENT for both students
        verify(attendanceRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).allMatch(a -> a.getStatus() == AttendanceStatus.PRESENT);
    }

    @Test
    @SuppressWarnings("unchecked")
    void startSessionPolling_doesNotDuplicateAttendanceIfAlreadyRecorded() throws Exception {
        when(meetClient.getActiveParticipantEmails("abc-def")).thenReturn(List.of("alice@meet.com"));
        when(studentRepository.findByMeetEmail("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmail("bob@meet.com")).thenReturn(Optional.of(bob));
        // Alice already has an attendance record
        when(attendanceRepository.findByStudentIdAndCalendarEventIdAndDate(eq(1L), anyString(), any()))
                .thenReturn(Optional.of(Attendance.builder().student(alice)
                        .calendarEventId("evt-1").date(LocalDate.now())
                        .status(AttendanceStatus.PRESENT).build()));
        when(taskScheduler.scheduleAtFixedRate(any(Runnable.class), any(Duration.class)))
                .thenReturn(mock(ScheduledFuture.class));

        monitor.startSessionPolling(event);

        // Only one save (for bob, who would have been absent if present — but bob isn't active here)
        // Alice's duplicate is suppressed
        verify(attendanceRepository, never()).save(argThat(a -> a.getStudent().equals(alice)));
    }

    // --- startSessionPolling (polling tick) ---

    @Test
    @SuppressWarnings("unchecked")
    void pollingTick_marksLateAndNotifiesForDelayedJoiner() throws Exception {
        // Nobody present at T+0
        when(meetClient.getActiveParticipantEmails("abc-def"))
                .thenReturn(List.of())                          // initial snapshot: empty
                .thenReturn(List.of("alice@meet.com"));         // first poll: Alice joins
        when(studentRepository.findByMeetEmail("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmail("bob@meet.com")).thenReturn(Optional.of(bob));
        when(attendanceRepository.findByStudentIdAndCalendarEventIdAndDate(anyLong(), anyString(), any()))
                .thenReturn(Optional.empty());

        // Capture the polling runnable so we can invoke it manually
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        when(taskScheduler.scheduleAtFixedRate(runnableCaptor.capture(), any(Duration.class)))
                .thenReturn(mock(ScheduledFuture.class));

        monitor.startSessionPolling(event);

        // Simulate one poll tick
        runnableCaptor.getValue().run();

        // Alice joined late
        ArgumentCaptor<Attendance> attendanceCaptor = ArgumentCaptor.forClass(Attendance.class);
        verify(attendanceRepository, org.mockito.Mockito.atLeastOnce()).save(attendanceCaptor.capture());
        assertThat(attendanceCaptor.getAllValues())
                .anyMatch(a -> a.getStudent().equals(alice) && a.getStatus() == AttendanceStatus.LATE);
        verify(notificationService).notifyArrival(event, alice);
        verify(notificationService).notifyLate(event, alice);
    }

    @Test
    @SuppressWarnings("unchecked")
    void pollingTick_notifiesAllPresentWhenEveryoneAccountedFor() throws Exception {
        // Both present at T+0
        when(meetClient.getActiveParticipantEmails("abc-def"))
                .thenReturn(List.of("alice@meet.com", "bob@meet.com"));
        when(studentRepository.findByMeetEmail("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmail("bob@meet.com")).thenReturn(Optional.of(bob));
        when(attendanceRepository.findByStudentIdAndCalendarEventIdAndDate(anyLong(), anyString(), any()))
                .thenReturn(Optional.empty());

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        when(taskScheduler.scheduleAtFixedRate(runnableCaptor.capture(), any(Duration.class)))
                .thenReturn(mock(ScheduledFuture.class));

        monitor.startSessionPolling(event);

        // Trigger poll — both already in seenIds, so notifyAllPresent fires
        runnableCaptor.getValue().run();

        verify(notificationService).notifyAllPresent(event);
    }

    // --- finalizeSession ---

    @Test
    void finalizeSession_marksAbsentAndNotifiesForMissingStudents() {
        when(studentRepository.findByMeetEmail("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmail("bob@meet.com")).thenReturn(Optional.of(bob));
        // No attendance record exists for either student
        when(attendanceRepository.findByStudentIdAndCalendarEventIdAndDate(anyLong(), anyString(), any()))
                .thenReturn(Optional.empty());

        monitor.finalizeSession(event);

        ArgumentCaptor<Attendance> captor = ArgumentCaptor.forClass(Attendance.class);
        verify(attendanceRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).allMatch(a -> a.getStatus() == AttendanceStatus.ABSENT);
        verify(notificationService).notifyAbsent(event, alice);
        verify(notificationService).notifyAbsent(event, bob);
    }

    @Test
    void finalizeSession_doesNotMarkAbsentIfAttendanceAlreadyRecorded() {
        when(studentRepository.findByMeetEmail("alice@meet.com")).thenReturn(Optional.of(alice));
        when(studentRepository.findByMeetEmail("bob@meet.com")).thenReturn(Optional.of(bob));
        // Alice already marked present
        when(attendanceRepository.findByStudentIdAndCalendarEventIdAndDate(eq(1L), anyString(), any()))
                .thenReturn(Optional.of(Attendance.builder().student(alice)
                        .calendarEventId("evt-1").date(LocalDate.now())
                        .status(AttendanceStatus.PRESENT).build()));
        // Bob has no record
        when(attendanceRepository.findByStudentIdAndCalendarEventIdAndDate(eq(2L), anyString(), any()))
                .thenReturn(Optional.empty());

        monitor.finalizeSession(event);

        verify(notificationService, never()).notifyAbsent(event, alice);
        verify(notificationService).notifyAbsent(event, bob);
    }

    // --- helper ---

    private static <T> T argThat(org.mockito.ArgumentMatcher<T> matcher) {
        return org.mockito.Mockito.argThat(matcher);
    }
}
