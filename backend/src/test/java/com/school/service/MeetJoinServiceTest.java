package com.school.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.school.config.AutoJoinProperties;
import com.school.entity.JoinAttemptLog;
import com.school.integration.MeetClient;
import com.school.model.CalendarEvent;
import com.school.repository.JoinAttemptLogRepository;

@ExtendWith(MockitoExtension.class)
class MeetJoinServiceTest {

    @Mock JoinAttemptLogRepository joinAttemptLogRepository;
    @Mock NotificationService notificationService;
    @Mock MeetClient meetClient;
    @Mock NoopJoinAutomationClient noopJoinAutomationClient;
    @Mock PlaywrightJoinAutomationClient playwrightJoinAutomationClient;

    private AutoJoinProperties autoJoinProperties;
    private MeetJoinService meetJoinService;
    private CalendarEvent event;

    @BeforeEach
    void setUp() {
        autoJoinProperties = new AutoJoinProperties();
        autoJoinProperties.setEnabled(true);
        autoJoinProperties.setProvider("noop");
        autoJoinProperties.setRetryMaxAttempts(1);
        meetJoinService = new MeetJoinService(
                autoJoinProperties,
                joinAttemptLogRepository,
                notificationService,
                meetClient,
                noopJoinAutomationClient,
                playwrightJoinAutomationClient);

        event = new CalendarEvent("evt-1", "Math Class",
                "https://meet.google.com/abc-def", "abc-def",
                LocalDateTime.now().plusMinutes(5), LocalDateTime.now().plusMinutes(65), List.of());
    }

    @Test
    void attemptScheduledJoin_returnsExistingLogWhenAlreadyAttemptedToday() {
        JoinAttemptLog existing = JoinAttemptLog.builder()
                .id(99L)
                .calendarEventId("evt-1")
                .status(JoinAttemptStatus.FAILED_UNKNOWN.name())
                .reasonCode("already_attempted")
                .build();
        when(joinAttemptLogRepository.existsByCalendarEventIdAndDateAndTriggerType(anyString(), any(), anyString()))
                .thenReturn(true);
        when(joinAttemptLogRepository.findTopByCalendarEventIdAndDateAndTriggerTypeOrderByAttemptedAtDesc(anyString(), any(), anyString()))
                .thenReturn(Optional.of(existing));

        JoinAttemptLog result = meetJoinService.attemptScheduledJoin(event);

        assertThat(result).isEqualTo(existing);
        verify(noopJoinAutomationClient, never()).attemptJoin(any());
    }

    @Test
    void attemptScheduledJoin_savesAndNotifiesOnFailure() {
        JoinAttemptLog saved = JoinAttemptLog.builder()
                .id(1L)
                .calendarEventId("evt-1")
                .status(JoinAttemptStatus.FAILED_WAITING_ROOM_TIMEOUT.name())
                .reasonCode("waiting_room_timeout")
                .detailMessage("timed out")
                .build();
        when(joinAttemptLogRepository.existsByCalendarEventIdAndDateAndTriggerType(anyString(), any(), anyString()))
                .thenReturn(false);
        when(noopJoinAutomationClient.attemptJoin(event)).thenReturn(new JoinAutomationResult(
                JoinAttemptStatus.FAILED_WAITING_ROOM_TIMEOUT,
                "waiting_room_timeout",
                "timed out"));
        when(joinAttemptLogRepository.save(any())).thenReturn(saved);

        JoinAttemptLog result = meetJoinService.attemptScheduledJoin(event);

        assertThat(result.getStatus()).isEqualTo(JoinAttemptStatus.FAILED_WAITING_ROOM_TIMEOUT.name());
        verify(notificationService).notify(any(), any(), any());
    }

    @Test
    void attemptManualJoin_bypassesScheduledIdempotency() {
        when(joinAttemptLogRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(noopJoinAutomationClient.attemptJoin(event)).thenReturn(new JoinAutomationResult(
                JoinAttemptStatus.FAILED_UNKNOWN,
                "provider_noop",
                "noop"));

        JoinAttemptLog first = meetJoinService.attemptManualJoin(event);
        JoinAttemptLog second = meetJoinService.attemptManualJoin(event);

        assertThat(first.getTriggerType()).isEqualTo("MANUAL");
        assertThat(second.getTriggerType()).isEqualTo("MANUAL");
    }
}
