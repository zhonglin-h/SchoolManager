package com.school.service.meet;

import com.school.entity.Attendance;
import com.school.entity.Person;
import com.school.entity.PersonType;
import com.school.integration.MeetClient;
import com.school.integration.MeetParticipant;
import com.school.model.CalendarEvent;
import com.school.repository.AttendanceRepository;
import com.school.repository.PersonRepository;
import com.school.service.NotificationService;
import com.school.service.NotificationSubject;
import com.school.service.NotificationType;
import com.school.service.PollingDeltaSubject;
import com.school.service.SessionFinalSummarySubject;
import com.school.service.UpcomingChecksRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MeetSessionServicesTest {

    @Mock PersonRepository personRepository;
    @Mock AttendanceRepository attendanceRepository;
    @Mock NotificationService notificationService;
    @Mock MeetClient meetClient;
    @Mock ThreadPoolTaskScheduler taskScheduler;
    @Mock UpcomingChecksRegistry upcomingChecksRegistry;

    private MeetParticipantResolver participantResolver;
    private MeetSessionPollingService pollingService;
    private MeetSessionFinalizer finalizer;

    private CalendarEvent event;
    private Person student;
    private Person teacher;

    @BeforeEach
    void setUp() {
        participantResolver = new MeetParticipantResolver(personRepository);
        pollingService = new MeetSessionPollingService(participantResolver, notificationService,
                meetClient, taskScheduler, attendanceRepository, upcomingChecksRegistry);
        finalizer = new MeetSessionFinalizer(participantResolver, notificationService,
                meetClient, attendanceRepository);

        ReflectionTestUtils.setField(pollingService, "lateBufferMinutes", 5);
        ReflectionTestUtils.setField(finalizer, "lateBufferMinutes", 5);

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
    void startSessionPolling_initialSnapshotUsesConsolidatedDeltaAndNotIndividualAlerts() throws Exception {
        stubExpectedPeopleAndNameMatching(null);
        when(attendanceRepository.findByPersonIdAndCalendarEventIdAndDate(anyLong(), eq("evt-1"), any(LocalDate.class)))
                .thenReturn(Optional.empty());
        when(meetClient.isMeetingActive("abc-def")).thenReturn(true);
        when(meetClient.getActiveParticipants("abc-def"))
                .thenReturn(List.of(
                        new MeetParticipant(null, "Alice", Instant.now().minusSeconds(30)),
                        new MeetParticipant(null, "Carol", Instant.now().minusSeconds(30))));

        pollingService.startSessionPolling(event);

        ArgumentCaptor<NotificationSubject> subjectCaptor = ArgumentCaptor.forClass(NotificationSubject.class);
        verify(notificationService).notify(eq(NotificationType.POLLING_DELTA), eq(event), subjectCaptor.capture());
        PollingDeltaSubject delta = (PollingDeltaSubject) subjectCaptor.getValue();
        assertThat(delta.arrivedNames()).containsExactlyInAnyOrder("Alice", "Carol");
        assertThat(delta.newlyMissingNames()).isEmpty();

        verify(notificationService).notify(NotificationType.ALL_PRESENT, event, null);
        verify(upcomingChecksRegistry).cancel("evt-1", "NOT_YET_JOINED_5");
        verify(notificationService, never()).notify(eq(NotificationType.ARRIVAL), eq(event), any());
        verify(notificationService, never()).notify(eq(NotificationType.LATE), eq(event), any());
        verify(taskScheduler, never()).scheduleAtFixedRate(any(Runnable.class), any(Duration.class));
    }

    @Test
    void pollingTickWithNoChanges_sendsNoDeltaNotification() throws Exception {
        stubExpectedPeopleAndNameMatching(null);
        when(attendanceRepository.findByPersonIdAndCalendarEventIdAndDate(anyLong(), eq("evt-1"), any(LocalDate.class)))
                .thenReturn(Optional.empty());
        when(meetClient.isMeetingActive("abc-def")).thenReturn(true);
        when(meetClient.getActiveParticipants("abc-def"))
                .thenReturn(List.of(new MeetParticipant(null, "Alice", Instant.now().minusSeconds(30))));

        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        when(taskScheduler.scheduleAtFixedRate(runnableCaptor.capture(), any(Duration.class)))
                .thenReturn((ScheduledFuture) future);

        pollingService.startSessionPolling(event);
        clearInvocations(notificationService);

        runnableCaptor.getValue().run();

        verify(notificationService, never()).notify(eq(NotificationType.POLLING_DELTA), eq(event), any());
        verify(notificationService, never()).notify(eq(NotificationType.ALL_PRESENT), eq(event), any());
    }

    @Test
    void completionTick_sendsDeltaThenAllPresentAndCancelsPolling() throws Exception {
        stubExpectedPeopleAndNameMatching(null);
        when(attendanceRepository.findByPersonIdAndCalendarEventIdAndDate(anyLong(), eq("evt-1"), any(LocalDate.class)))
                .thenReturn(Optional.empty());
        when(meetClient.isMeetingActive("abc-def")).thenReturn(true);
        when(meetClient.getActiveParticipants("abc-def"))
                .thenReturn(
                        List.of(new MeetParticipant(null, "Alice", Instant.now().minusSeconds(30))),
                        List.of(
                                new MeetParticipant(null, "Alice", Instant.now().minusSeconds(30)),
                                new MeetParticipant(null, "Carol", Instant.now().minusSeconds(20))));

        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        when(taskScheduler.scheduleAtFixedRate(runnableCaptor.capture(), any(Duration.class)))
                .thenReturn((ScheduledFuture) future);

        pollingService.startSessionPolling(event);
        clearInvocations(notificationService);

        runnableCaptor.getValue().run();

        InOrder inOrder = inOrder(notificationService);
        inOrder.verify(notificationService).notify(eq(NotificationType.POLLING_DELTA), eq(event), any());
        inOrder.verify(notificationService).notify(NotificationType.ALL_PRESENT, event, null);
        verify(upcomingChecksRegistry).cancel("evt-1", "NOT_YET_JOINED_5");
        verify(future).cancel(false);
    }

    @Test
    void finalizeSession_sendsConsolidatedSummaryWithoutPerPersonFinalAlerts() throws Exception {
        Person knownUnexpected = Person.builder().id(3L).personType(PersonType.STUDENT)
                .name("Eve").meetEmail("eve@meet.com").build();

        stubExpectedPeopleAndNameMatching(knownUnexpected);
        when(attendanceRepository.findByPersonIdAndCalendarEventIdAndDate(1L, "evt-1", LocalDate.now()))
                .thenReturn(Optional.empty());
        when(attendanceRepository.findByPersonIdAndCalendarEventIdAndDate(2L, "evt-1", LocalDate.now()))
                .thenReturn(Optional.empty());
        when(meetClient.getAllParticipants("abc-def")).thenReturn(List.of(
                new MeetParticipant(null, "Alice", Instant.now().minusSeconds(60)),
                new MeetParticipant(null, "Eve", Instant.now().minusSeconds(50)),
                new MeetParticipant(null, "Mystery", Instant.now().minusSeconds(40))
        ));

        finalizer.finalizeSession(event);

        ArgumentCaptor<NotificationSubject> subjectCaptor = ArgumentCaptor.forClass(NotificationSubject.class);
        verify(notificationService).notify(eq(NotificationType.SESSION_FINAL_SUMMARY), eq(event), subjectCaptor.capture());
        SessionFinalSummarySubject summary = (SessionFinalSummarySubject) subjectCaptor.getValue();
        assertThat(summary.presentNames()).contains("Alice");
        assertThat(summary.absentNames()).contains("Carol");
        assertThat(summary.knownButUnexpectedNames()).contains("Eve");
        assertThat(summary.notInSystemNames()).contains("Mystery");

        verify(notificationService, never()).notify(eq(NotificationType.ARRIVAL), eq(event), any());
        verify(notificationService, never()).notify(eq(NotificationType.LATE), eq(event), any());
        verify(notificationService, never()).notify(eq(NotificationType.ABSENT), eq(event), any());

        ArgumentCaptor<Attendance> attendanceCaptor = ArgumentCaptor.forClass(Attendance.class);
        verify(attendanceRepository, times(2)).save(attendanceCaptor.capture());
        assertThat(attendanceCaptor.getAllValues()).hasSize(2);
    }

    @Test
    void checkMeetingStarted_notifiesWhenRoomIsNotActive() throws Exception {
        when(meetClient.isMeetingActive(anyString())).thenReturn(false);

        pollingService.checkMeetingStarted(event, NotificationType.MEETING_NOT_STARTED_15);

        verify(notificationService).notify(NotificationType.MEETING_NOT_STARTED_15, event, null);
    }

    private void stubExpectedPeopleAndNameMatching(Person extraKnown) {
        when(personRepository.findByMeetEmailInAndActiveTrue(any())).thenAnswer(invocation -> {
            List<String> emails = invocation.getArgument(0);
            List<Person> matched = new ArrayList<>();
            for (String email : emails) {
                if ("alice@meet.com".equalsIgnoreCase(email)) {
                    matched.add(student);
                } else if ("carol@meet.com".equalsIgnoreCase(email)) {
                    matched.add(teacher);
                } else if (extraKnown != null && extraKnown.getMeetEmail() != null
                        && extraKnown.getMeetEmail().equalsIgnoreCase(email)) {
                    matched.add(extraKnown);
                }
            }
            return matched;
        });
        when(personRepository.findByPersonTypeAndMeetDisplayNameIgnoreCaseAndActiveTrue(any(), anyString()))
                .thenReturn(Optional.empty());
        when(personRepository.findByPersonTypeAndNameIgnoreCaseAndActiveTrue(any(), anyString()))
                .thenAnswer(invocation -> {
                    PersonType type = invocation.getArgument(0);
                    String name = invocation.getArgument(1);
                    if (type == PersonType.STUDENT && "Alice".equalsIgnoreCase(name)) {
                        return Optional.of(student);
                    }
                    if (type == PersonType.TEACHER && "Carol".equalsIgnoreCase(name)) {
                        return Optional.of(teacher);
                    }
                    if (extraKnown != null
                            && type == extraKnown.getPersonType()
                            && extraKnown.getName().equalsIgnoreCase(name)) {
                        return Optional.of(extraKnown);
                    }
                    return Optional.empty();
                });
    }
}
