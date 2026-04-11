package com.school.service;

import com.school.entity.NotificationLog;
import com.school.entity.Student;
import com.school.integration.EmailClient;
import com.school.model.CalendarEvent;
import com.school.repository.NotificationLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    NotificationLogRepository notificationLogRepository;

    @Mock
    EmailClient emailClient;

    private static final String PRINCIPAL = "principal@test.com";

    NotificationService notificationService;

    private CalendarEvent event;
    private Student student;

    @BeforeEach
    void setUp() {
        notificationService = new NotificationService(notificationLogRepository, emailClient, PRINCIPAL, true);

        event = new CalendarEvent("evt-1", "Math Class", "https://meet.google.com/abc",
                "abc", LocalDateTime.now(), LocalDateTime.now().plusHours(1), List.of());

        student = Student.builder()
                .id(1L).name("Alice").meetEmail("alice@meet.com")
                .classroomEmail("alice@class.com")
                .parentEmail("alice-parent@test.com").parentPhone("555-1111")
                .build();
    }

    // --- MEETING_NOT_STARTED_15 ---

    @Test
    void notifyMeetingNotStarted_sendsEmailAndSavesLog() {
        when(notificationLogRepository.existsByCalendarEventIdAndDateAndTypeAndStudentIsNullAndSuccessTrue(
                anyString(), any(LocalDate.class), anyString())).thenReturn(false);

        notificationService.notify(NotificationType.MEETING_NOT_STARTED_15, event, null);

        verify(emailClient).send(eq(PRINCIPAL), anyString(), anyString());
        verify(notificationLogRepository).save(any(NotificationLog.class));
    }

    @Test
    void notifyMeetingNotStarted_skipsDuplicate() {
        when(notificationLogRepository.existsByCalendarEventIdAndDateAndTypeAndStudentIsNullAndSuccessTrue(
                anyString(), any(LocalDate.class), anyString())).thenReturn(true);

        notificationService.notify(NotificationType.MEETING_NOT_STARTED_15, event, null);

        verify(emailClient, never()).send(anyString(), anyString(), anyString());
        verify(notificationLogRepository, never()).save(any());
    }

    // --- NOT_YET_JOINED_3 ---

    @Test
    void notifyNotYetJoined_sendsToPrincipalAndParent() {
        when(notificationLogRepository.existsByStudentIdAndCalendarEventIdAndDateAndTypeAndSuccessTrue(
                anyLong(), anyString(), any(LocalDate.class), anyString())).thenReturn(false);

        notificationService.notify(NotificationType.NOT_YET_JOINED_3, event, student);

        ArgumentCaptor<String> toCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailClient, times(2)).send(toCaptor.capture(), anyString(), anyString());
        assertThat(toCaptor.getAllValues()).containsExactlyInAnyOrder(PRINCIPAL, "alice-parent@test.com");
        verify(notificationLogRepository).save(any(NotificationLog.class));
    }

    @Test
    void notifyNotYetJoined_skipsDuplicate() {
        when(notificationLogRepository.existsByStudentIdAndCalendarEventIdAndDateAndTypeAndSuccessTrue(
                anyLong(), anyString(), any(LocalDate.class), anyString())).thenReturn(true);

        notificationService.notify(NotificationType.NOT_YET_JOINED_3, event, student);

        verify(emailClient, never()).send(anyString(), anyString(), anyString());
    }

    // --- ARRIVAL ---

    @Test
    void notifyArrival_sendsToPrincipalOnly() {
        when(notificationLogRepository.existsByStudentIdAndCalendarEventIdAndDateAndTypeAndSuccessTrue(
                anyLong(), anyString(), any(LocalDate.class), anyString())).thenReturn(false);

        notificationService.notify(NotificationType.ARRIVAL, event, student);

        ArgumentCaptor<String> toCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailClient, times(1)).send(toCaptor.capture(), anyString(), anyString());
        assertThat(toCaptor.getValue()).isEqualTo(PRINCIPAL);
    }

    @Test
    void notifyArrival_skipsDuplicate() {
        when(notificationLogRepository.existsByStudentIdAndCalendarEventIdAndDateAndTypeAndSuccessTrue(
                anyLong(), anyString(), any(LocalDate.class), anyString())).thenReturn(true);

        notificationService.notify(NotificationType.ARRIVAL, event, student);

        verify(emailClient, never()).send(anyString(), anyString(), anyString());
    }

    // --- ALL_PRESENT ---

    @Test
    void notifyAllPresent_sendsToPrincipalOnly() {
        when(notificationLogRepository.existsByCalendarEventIdAndDateAndTypeAndStudentIsNullAndSuccessTrue(
                anyString(), any(LocalDate.class), anyString())).thenReturn(false);

        notificationService.notify(NotificationType.ALL_PRESENT, event, null);

        ArgumentCaptor<String> toCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailClient, times(1)).send(toCaptor.capture(), anyString(), anyString());
        assertThat(toCaptor.getValue()).isEqualTo(PRINCIPAL);
    }

    @Test
    void notifyAllPresent_skipsDuplicate() {
        when(notificationLogRepository.existsByCalendarEventIdAndDateAndTypeAndStudentIsNullAndSuccessTrue(
                anyString(), any(LocalDate.class), anyString())).thenReturn(true);

        notificationService.notify(NotificationType.ALL_PRESENT, event, null);

        verify(emailClient, never()).send(anyString(), anyString(), anyString());
    }

    // --- LATE ---

    @Test
    void notifyLate_sendsToPrincipalAndParent() {
        when(notificationLogRepository.existsByStudentIdAndCalendarEventIdAndDateAndTypeAndSuccessTrue(
                anyLong(), anyString(), any(LocalDate.class), anyString())).thenReturn(false);

        notificationService.notify(NotificationType.LATE, event, student);

        ArgumentCaptor<String> toCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailClient, times(2)).send(toCaptor.capture(), anyString(), anyString());
        assertThat(toCaptor.getAllValues()).containsExactlyInAnyOrder(PRINCIPAL, "alice-parent@test.com");
    }

    @Test
    void notifyLate_skipsDuplicate() {
        when(notificationLogRepository.existsByStudentIdAndCalendarEventIdAndDateAndTypeAndSuccessTrue(
                anyLong(), anyString(), any(LocalDate.class), anyString())).thenReturn(true);

        notificationService.notify(NotificationType.LATE, event, student);

        verify(emailClient, never()).send(anyString(), anyString(), anyString());
    }

    // --- ABSENT ---

    @Test
    void notifyAbsent_sendsToParentOnly() {
        when(notificationLogRepository.existsByStudentIdAndCalendarEventIdAndDateAndTypeAndSuccessTrue(
                anyLong(), anyString(), any(LocalDate.class), anyString())).thenReturn(false);

        notificationService.notify(NotificationType.ABSENT, event, student);

        ArgumentCaptor<String> toCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailClient, times(1)).send(toCaptor.capture(), anyString(), anyString());
        assertThat(toCaptor.getValue()).isEqualTo("alice-parent@test.com");
    }

    @Test
    void notifyAbsent_skipsDuplicate() {
        when(notificationLogRepository.existsByStudentIdAndCalendarEventIdAndDateAndTypeAndSuccessTrue(
                anyLong(), anyString(), any(LocalDate.class), anyString())).thenReturn(true);

        notificationService.notify(NotificationType.ABSENT, event, student);

        verify(emailClient, never()).send(anyString(), anyString(), anyString());
    }

    // --- log content ---

    @Test
    void savedLog_hasCorrectFields() {
        when(notificationLogRepository.existsByStudentIdAndCalendarEventIdAndDateAndTypeAndSuccessTrue(
                anyLong(), anyString(), any(LocalDate.class), anyString())).thenReturn(false);

        notificationService.notify(NotificationType.ABSENT, event, student);

        ArgumentCaptor<NotificationLog> logCaptor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepository).save(logCaptor.capture());
        NotificationLog log = logCaptor.getValue();
        assertThat(log.getCalendarEventId()).isEqualTo("evt-1");
        assertThat(log.getType()).isEqualTo("ABSENT");
        assertThat(log.getStudent()).isEqualTo(student);
        assertThat(log.getDate()).isEqualTo(LocalDate.now());
        assertThat(log.getRecipient()).isEqualTo("alice-parent@test.com");
    }

    @Test
    void savedLog_recipientContainsPrincipalWhenToPrincipal() {
        when(notificationLogRepository.existsByCalendarEventIdAndDateAndTypeAndStudentIsNullAndSuccessTrue(
                anyString(), any(LocalDate.class), anyString())).thenReturn(false);

        notificationService.notify(NotificationType.ALL_PRESENT, event, null);

        ArgumentCaptor<NotificationLog> logCaptor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getRecipient()).isEqualTo(PRINCIPAL);
    }

    @Test
    void savedLog_recipientContainsBothWhenToPrincipalAndParent() {
        when(notificationLogRepository.existsByStudentIdAndCalendarEventIdAndDateAndTypeAndSuccessTrue(
                anyLong(), anyString(), any(LocalDate.class), anyString())).thenReturn(false);

        notificationService.notify(NotificationType.NOT_YET_JOINED_3, event, student);

        ArgumentCaptor<NotificationLog> logCaptor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getRecipient())
                .isEqualTo(PRINCIPAL + ", " + "alice-parent@test.com");
    }

    @Test
    void principalOnlyLog_hasNullStudent() {
        when(notificationLogRepository.existsByCalendarEventIdAndDateAndTypeAndStudentIsNullAndSuccessTrue(
                anyString(), any(LocalDate.class), anyString())).thenReturn(false);

        notificationService.notify(NotificationType.ALL_PRESENT, event, null);

        ArgumentCaptor<NotificationLog> logCaptor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getStudent()).isNull();
    }
}
