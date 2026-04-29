package com.school.service;

import com.school.entity.JoinAttemptLog;
import com.school.entity.JoinAttemptStatus;
import com.school.integration.JoinAutomationClient;
import com.school.integration.JoinResult;
import com.school.model.CalendarEvent;
import com.school.repository.JoinAttemptLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JoinAttemptServiceTest {

    @Mock JoinAutomationClient joinAutomationClient;
    @Mock JoinAttemptLogRepository joinAttemptLogRepository;
    @Mock NotificationService notificationService;

    private JoinAttemptService service;
    private CalendarEvent event;

    @BeforeEach
    void setUp() {
        service = new JoinAttemptService(joinAutomationClient, joinAttemptLogRepository, notificationService);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "notifyOnSuccess", false);

        event = new CalendarEvent(
                "evt-1",
                "Math Class",
                "https://meet.google.com/abc-def",
                "abc-def",
                LocalDateTime.now().plusMinutes(1),
                LocalDateTime.now().plusMinutes(61),
                List.of("alice@meet.com")
        );

        when(joinAttemptLogRepository.save(any(JoinAttemptLog.class)))
                .thenAnswer(invocation -> {
                    JoinAttemptLog entry = invocation.getArgument(0);
                    ReflectionTestUtils.setField(entry, "id", 1L);
                    return entry;
                });
    }

    @Test
    void attemptJoinIfEnabled_doesNothingWhenDisabled() {
        ReflectionTestUtils.setField(service, "enabled", false);

        service.attemptJoinIfEnabled(event, "AUTO");

        verify(joinAutomationClient, never()).attemptJoin(any());
        verify(joinAttemptLogRepository, never()).save(any());
    }

    @Test
    void attemptJoinIfEnabled_callsAttemptJoinWhenEnabled() {
        when(joinAutomationClient.attemptJoin(event))
                .thenReturn(new JoinResult(JoinAttemptStatus.JOINED, "ok"));

        service.attemptJoinIfEnabled(event, "AUTO");

        verify(joinAutomationClient).attemptJoin(event);
    }

    @Test
    void attemptJoin_recordsFailedUnknownWhenNoMeetLink() {
        CalendarEvent noLink = new CalendarEvent(
                "evt-noop", "No Link", null, null,
                LocalDateTime.now().plusMinutes(1), LocalDateTime.now().plusMinutes(61), List.of()
        );

        JoinAttemptLog result = service.attemptJoin(noLink, "AUTO");

        assertThat(result.getStatus()).isEqualTo(JoinAttemptStatus.FAILED_UNKNOWN);
        verify(joinAutomationClient, never()).attemptJoin(any());
    }

    @Test
    void attemptJoin_recordsJoinedStatusOnSuccess() {
        when(joinAutomationClient.attemptJoin(event))
                .thenReturn(new JoinResult(JoinAttemptStatus.JOINED, "Joined successfully"));

        JoinAttemptLog result = service.attemptJoin(event, "AUTO");

        assertThat(result.getStatus()).isEqualTo(JoinAttemptStatus.JOINED);
        assertThat(result.getDetailMessage()).isEqualTo("Joined successfully");
        assertThat(result.getCalendarEventId()).isEqualTo("evt-1");
        assertThat(result.getTriggerType()).isEqualTo("AUTO");
    }

    @Test
    void attemptJoin_doesNotNotifyOnSuccessWhenFlagIsFalse() {
        when(joinAutomationClient.attemptJoin(event))
                .thenReturn(new JoinResult(JoinAttemptStatus.JOINED, "ok"));

        service.attemptJoin(event, "AUTO");

        verify(notificationService, never()).notify(eq(NotificationType.AUTO_JOIN_SUCCESS), any(), any());
    }

    @Test
    void attemptJoin_notifiesOnSuccessWhenFlagIsTrue() {
        ReflectionTestUtils.setField(service, "notifyOnSuccess", true);
        when(joinAutomationClient.attemptJoin(event))
                .thenReturn(new JoinResult(JoinAttemptStatus.JOINED, "ok"));

        service.attemptJoin(event, "AUTO");

        verify(notificationService).notify(NotificationType.AUTO_JOIN_SUCCESS, event, null);
    }

    @Test
    void attemptJoin_recordsFailureStatusAndNotifiesOnFailure() {
        when(joinAutomationClient.attemptJoin(event))
                .thenReturn(new JoinResult(JoinAttemptStatus.FAILED_AUTH, "Token expired"));

        JoinAttemptLog result = service.attemptJoin(event, "AUTO");

        assertThat(result.getStatus()).isEqualTo(JoinAttemptStatus.FAILED_AUTH);
        assertThat(result.getDetailMessage()).isEqualTo("Token expired");
        verify(notificationService).notify(NotificationType.AUTO_JOIN_FAILED, event, null);
    }

    @Test
    void attemptJoin_notifiesFailedWhenClientThrowsException() {
        when(joinAutomationClient.attemptJoin(event)).thenThrow(new RuntimeException("crash"));

        JoinAttemptLog result = service.attemptJoin(event, "AUTO");

        assertThat(result.getStatus()).isEqualTo(JoinAttemptStatus.FAILED_UNKNOWN);
        assertThat(result.getDetailMessage()).isEqualTo("crash");
        verify(notificationService).notify(NotificationType.AUTO_JOIN_FAILED, event, null);
    }

    @Test
    void attemptJoin_recordsCorrectCalendarFields() {
        when(joinAutomationClient.attemptJoin(event))
                .thenReturn(new JoinResult(JoinAttemptStatus.JOINED, "ok"));

        service.attemptJoin(event, "MANUAL");

        ArgumentCaptor<JoinAttemptLog> captor = ArgumentCaptor.forClass(JoinAttemptLog.class);
        verify(joinAttemptLogRepository).save(captor.capture());
        JoinAttemptLog saved = captor.getValue();
        assertThat(saved.getCalendarEventId()).isEqualTo("evt-1");
        assertThat(saved.getScheduledStart()).isEqualTo(event.getStartTime());
        assertThat(saved.getTriggerType()).isEqualTo("MANUAL");
    }

    @Test
    void getTodayAttempts_delegatesToRepository() {
        JoinAttemptLog log = JoinAttemptLog.builder()
                .id(5L)
                .calendarEventId("evt-1")
                .triggerType("AUTO")
                .status(JoinAttemptStatus.JOINED)
                .attemptedAt(LocalDateTime.now())
                .build();
        LocalDateTime start = LocalDate.now().atStartOfDay();
        LocalDateTime end = start.plusDays(1);
        when(joinAttemptLogRepository.findByAttemptedAtBetweenOrderByAttemptedAtDesc(start, end))
                .thenReturn(List.of(log));

        List<JoinAttemptLog> result = service.getTodayAttempts();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getId()).isEqualTo(5L);
    }
}
