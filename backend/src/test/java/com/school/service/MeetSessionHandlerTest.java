package com.school.service;

import com.school.entity.Attendance;
import com.school.entity.AttendanceStatus;
import com.school.entity.Person;
import com.school.entity.PersonType;
import com.school.integration.MeetClient;
import com.school.integration.MeetParticipant;
import com.school.model.CalendarEvent;
import com.school.repository.AttendanceRepository;
import com.school.repository.PersonRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MeetSessionHandlerTest {

    @Mock PersonRepository personRepository;
    @Mock AttendanceRepository attendanceRepository;
    @Mock NotificationService notificationService;
    @Mock MeetClient meetClient;
    @Mock ThreadPoolTaskScheduler taskScheduler;
    @Mock UpcomingChecksRegistry upcomingChecksRegistry;

    private MeetAttendanceHelper attendanceHelper;
    private MeetSessionHandler sessionHandler;

    private CalendarEvent event;
    private Person student;
    private Person teacher;

    @BeforeEach
    void setUp() {
        attendanceHelper = new MeetAttendanceHelper(personRepository, attendanceRepository, notificationService);
        sessionHandler = new MeetSessionHandler(attendanceHelper, notificationService,
                meetClient, taskScheduler, attendanceRepository, upcomingChecksRegistry);
        ReflectionTestUtils.setField(sessionHandler, "lateBufferMinutes", 5);

        event = new CalendarEvent("evt-1", "Math Class",
                "https://meet.google.com/abc-def", "abc-def",
                LocalDateTime.now().minusMinutes(2), LocalDateTime.now().plusMinutes(30),
                List.of("alice@meet.com", "carol@meet.com"));

        student = Person.builder().id(1L).personType(PersonType.STUDENT)
                .name("Alice").meetEmail("alice@meet.com").build();
        teacher = Person.builder().id(2L).personType(PersonType.TEACHER)
                .name("Carol").meetEmail("carol@meet.com").build();
    }

    @Test
    void checkPreClassJoins_notifiesMissingStudentAndTeacher() throws Exception {
        when(meetClient.getActiveParticipants("abc-def")).thenReturn(List.of());
        when(personRepository.findByMeetEmailAndActiveTrue("alice@meet.com"))
                .thenReturn(Optional.of(student));
        when(personRepository.findByMeetEmailAndActiveTrue("carol@meet.com"))
                .thenReturn(Optional.of(teacher));

        sessionHandler.checkPreClassJoins(event);

        verify(notificationService).notify(NotificationType.NOT_YET_JOINED, event, new PersonSubject(student));
        verify(notificationService).notify(NotificationType.NOT_YET_JOINED, event, new PersonSubject(teacher));
    }

    @Test
    void checkPreClassJoins_skipsPresentStudentMatchedByGoogleUserId() throws Exception {
        when(meetClient.getActiveParticipants("abc-def")).thenReturn(List.of(new MeetParticipant("uid-alice", "Alice", null)));
        when(personRepository.findByMeetEmailAndActiveTrue("alice@meet.com"))
                .thenReturn(Optional.of(student));
        when(personRepository.findByMeetEmailAndActiveTrue("carol@meet.com"))
                .thenReturn(Optional.of(teacher));
        when(personRepository.findByPersonTypeAndGoogleUserIdAndActiveTrue(PersonType.STUDENT, "uid-alice"))
                .thenReturn(Optional.of(student));

        sessionHandler.checkPreClassJoins(event);

        verify(notificationService, never()).notify(NotificationType.NOT_YET_JOINED, event, new PersonSubject(student));
        verify(notificationService).notify(NotificationType.NOT_YET_JOINED, event, new PersonSubject(teacher));
    }

    @Test
    void finalizeSession_marksAbsentAndSendsTypeSpecificNotifications() throws Exception {
        when(meetClient.getAllParticipants("abc-def")).thenReturn(List.of());
        when(personRepository.findByMeetEmailAndActiveTrue("alice@meet.com"))
                .thenReturn(Optional.of(student));
        when(personRepository.findByMeetEmailAndActiveTrue("carol@meet.com"))
                .thenReturn(Optional.of(teacher));
        when(attendanceRepository.findByPersonIdAndCalendarEventIdAndDate(1L, "evt-1", LocalDate.now()))
                .thenReturn(Optional.empty());
        when(attendanceRepository.findByPersonIdAndCalendarEventIdAndDate(2L, "evt-1", LocalDate.now()))
                .thenReturn(Optional.empty());

        sessionHandler.finalizeSession(event);

        ArgumentCaptor<Attendance> captor = ArgumentCaptor.forClass(Attendance.class);
        verify(attendanceRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(a -> a.getPerson().getPersonType())
                .containsExactlyInAnyOrder(PersonType.STUDENT, PersonType.TEACHER);
        verify(notificationService).notify(NotificationType.ABSENT, event, new PersonSubject(student));
        verify(notificationService).notify(NotificationType.ABSENT, event, new PersonSubject(teacher));
    }

    @Test
    void checkMeetingStarted_notifiesWhenRoomIsNotActive() throws Exception {
        when(meetClient.isMeetingActive(anyString())).thenReturn(false);

        sessionHandler.checkMeetingStarted(event, NotificationType.MEETING_NOT_STARTED_15);

        verify(notificationService).notify(NotificationType.MEETING_NOT_STARTED_15, event, null);
    }

    @Test
    void checkPreClassJoins_registeredTeacherNotFlaggedAsUnmatchedParticipant() throws Exception {
        event.setAttendeeEmails(List.of("victoriasupereducation.com"));
        Person victoria = Person.builder().id(3L).personType(PersonType.TEACHER)
                .name("Victoria Yin").meetEmail("victoria@supereducation.com").build();

        when(meetClient.getActiveParticipants("abc-def"))
                .thenReturn(List.of(new MeetParticipant(null, "Victoria Yin", null)));
        when(personRepository.findByMeetEmailAndActiveTrue("victoriasupereducation.com"))
                .thenReturn(Optional.empty());
        when(personRepository.findByPersonTypeAndMeetDisplayNameIgnoreCaseAndActiveTrue(PersonType.STUDENT, "Victoria Yin"))
                .thenReturn(Optional.empty());
        when(personRepository.findByPersonTypeAndNameIgnoreCaseAndActiveTrue(PersonType.STUDENT, "Victoria Yin"))
                .thenReturn(Optional.empty());
        when(personRepository.findByPersonTypeAndMeetDisplayNameIgnoreCaseAndActiveTrue(PersonType.TEACHER, "Victoria Yin"))
                .thenReturn(Optional.of(victoria));

        sessionHandler.checkPreClassJoins(event);

        ArgumentCaptor<NotificationSubject> subjectCaptor = ArgumentCaptor.forClass(NotificationSubject.class);
        verify(notificationService).notify(eq(NotificationType.UNMATCHED_GUESTS), eq(event), subjectCaptor.capture());
        GuestSubject guestSubject = (GuestSubject) subjectCaptor.getValue();
        assertThat(guestSubject.unmatchedInvitees()).containsExactly("victoriasupereducation.com");
        assertThat(guestSubject.unmatchedParticipants()).isEmpty();
    }
}
