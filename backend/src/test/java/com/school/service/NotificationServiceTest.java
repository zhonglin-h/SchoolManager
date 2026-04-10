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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

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

    @InjectMocks
    NotificationService notificationService;

    private static final String PRINCIPAL = "principal@test.com";

    private CalendarEvent event;
    private Student student;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(notificationService, "principalEmail", PRINCIPAL);
        ReflectionTestUtils.setField(notificationService, "notificationsEnabled", true);

        event = new CalendarEvent("evt-1", "Math Class", "https://meet.google.com/abc",
                "abc", LocalDateTime.now(), LocalDateTime.now().plusHours(1), List.of());

        student = Student.builder()
                .id(1L).name("Alice").meetEmail("alice@meet.com")
                .classroomEmail("alice@class.com")
                .parentEmail("alice-parent@test.com").parentPhone("555-1111")
                .build();
    }

    // --- notifyMeetingNotStarted ---

    @Test
    void notifyMeetingNotStarted_sendsEmailAndSavesLog() {
        when(notificationLogRepository.existsByCalendarEventIdAndDateAndTypeAndStudentIsNull(
                anyString(), any(LocalDate.class), anyString())).thenReturn(false);

        notificationService.notifyMeetingNotStarted(event, "MEETING_NOT_STARTED_15");

        verify(emailClient).send(eq(PRINCIPAL), anyString(), anyString());
        verify(notificationLogRepository).save(any(NotificationLog.class));
    }

    @Test
    void notifyMeetingNotStarted_skipsDuplicate() {
        when(notificationLogRepository.existsByCalendarEventIdAndDateAndTypeAndStudentIsNull(
                anyString(), any(LocalDate.class), anyString())).thenReturn(true);

        notificationService.notifyMeetingNotStarted(event, "MEETING_NOT_STARTED_15");

        verify(emailClient, never()).send(anyString(), anyString(), anyString());
        verify(notificationLogRepository, never()).save(any());
    }

    // --- notifyNotYetJoined ---

    @Test
    void notifyNotYetJoined_sendsToPrincipalAndParent() {
        when(notificationLogRepository.existsByStudentIdAndCalendarEventIdAndDateAndType(
                anyLong(), anyString(), any(LocalDate.class), anyString())).thenReturn(false);

        notificationService.notifyNotYetJoined(event, student);

        ArgumentCaptor<String> toCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailClient, times(2)).send(toCaptor.capture(), anyString(), anyString());
        assertThat(toCaptor.getAllValues()).containsExactlyInAnyOrder(PRINCIPAL, "alice-parent@test.com");
        verify(notificationLogRepository).save(any(NotificationLog.class));
    }

    @Test
    void notifyNotYetJoined_skipsDuplicate() {
        when(notificationLogRepository.existsByStudentIdAndCalendarEventIdAndDateAndType(
                anyLong(), anyString(), any(LocalDate.class), anyString())).thenReturn(true);

        notificationService.notifyNotYetJoined(event, student);

        verify(emailClient, never()).send(anyString(), anyString(), anyString());
    }

    // --- notifyArrival ---

    @Test
    void notifyArrival_sendsToPrincipalOnly() {
        when(notificationLogRepository.existsByStudentIdAndCalendarEventIdAndDateAndType(
                anyLong(), anyString(), any(LocalDate.class), anyString())).thenReturn(false);

        notificationService.notifyArrival(event, student);

        ArgumentCaptor<String> toCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailClient, times(1)).send(toCaptor.capture(), anyString(), anyString());
        assertThat(toCaptor.getValue()).isEqualTo(PRINCIPAL);
    }

    @Test
    void notifyArrival_skipsDuplicate() {
        when(notificationLogRepository.existsByStudentIdAndCalendarEventIdAndDateAndType(
                anyLong(), anyString(), any(LocalDate.class), anyString())).thenReturn(true);

        notificationService.notifyArrival(event, student);

        verify(emailClient, never()).send(anyString(), anyString(), anyString());
    }

    // --- notifyAllPresent ---

    @Test
    void notifyAllPresent_sendsToPrincipalOnly() {
        when(notificationLogRepository.existsByCalendarEventIdAndDateAndTypeAndStudentIsNull(
                anyString(), any(LocalDate.class), anyString())).thenReturn(false);

        notificationService.notifyAllPresent(event);

        ArgumentCaptor<String> toCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailClient, times(1)).send(toCaptor.capture(), anyString(), anyString());
        assertThat(toCaptor.getValue()).isEqualTo(PRINCIPAL);
    }

    @Test
    void notifyAllPresent_skipsDuplicate() {
        when(notificationLogRepository.existsByCalendarEventIdAndDateAndTypeAndStudentIsNull(
                anyString(), any(LocalDate.class), anyString())).thenReturn(true);

        notificationService.notifyAllPresent(event);

        verify(emailClient, never()).send(anyString(), anyString(), anyString());
    }

    // --- notifyLate ---

    @Test
    void notifyLate_sendsToPrincipalAndParent() {
        when(notificationLogRepository.existsByStudentIdAndCalendarEventIdAndDateAndType(
                anyLong(), anyString(), any(LocalDate.class), anyString())).thenReturn(false);

        notificationService.notifyLate(event, student);

        ArgumentCaptor<String> toCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailClient, times(2)).send(toCaptor.capture(), anyString(), anyString());
        assertThat(toCaptor.getAllValues()).containsExactlyInAnyOrder(PRINCIPAL, "alice-parent@test.com");
    }

    @Test
    void notifyLate_skipsDuplicate() {
        when(notificationLogRepository.existsByStudentIdAndCalendarEventIdAndDateAndType(
                anyLong(), anyString(), any(LocalDate.class), anyString())).thenReturn(true);

        notificationService.notifyLate(event, student);

        verify(emailClient, never()).send(anyString(), anyString(), anyString());
    }

    // --- notifyAbsent ---

    @Test
    void notifyAbsent_sendsToParentOnly() {
        when(notificationLogRepository.existsByStudentIdAndCalendarEventIdAndDateAndType(
                anyLong(), anyString(), any(LocalDate.class), anyString())).thenReturn(false);

        notificationService.notifyAbsent(event, student);

        ArgumentCaptor<String> toCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailClient, times(1)).send(toCaptor.capture(), anyString(), anyString());
        assertThat(toCaptor.getValue()).isEqualTo("alice-parent@test.com");
    }

    @Test
    void notifyAbsent_skipsDuplicate() {
        when(notificationLogRepository.existsByStudentIdAndCalendarEventIdAndDateAndType(
                anyLong(), anyString(), any(LocalDate.class), anyString())).thenReturn(true);

        notificationService.notifyAbsent(event, student);

        verify(emailClient, never()).send(anyString(), anyString(), anyString());
    }

    // --- log content ---

    @Test
    void savedLog_hasCorrectFields() {
        when(notificationLogRepository.existsByStudentIdAndCalendarEventIdAndDateAndType(
                anyLong(), anyString(), any(LocalDate.class), anyString())).thenReturn(false);

        notificationService.notifyAbsent(event, student);

        ArgumentCaptor<NotificationLog> logCaptor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepository).save(logCaptor.capture());
        NotificationLog log = logCaptor.getValue();
        assertThat(log.getCalendarEventId()).isEqualTo("evt-1");
        assertThat(log.getType()).isEqualTo("ABSENT");
        assertThat(log.getStudent()).isEqualTo(student);
        assertThat(log.getDate()).isEqualTo(LocalDate.now());
    }

    @Test
    void principalOnlyLog_hasNullStudent() {
        when(notificationLogRepository.existsByCalendarEventIdAndDateAndTypeAndStudentIsNull(
                anyString(), any(LocalDate.class), anyString())).thenReturn(false);

        notificationService.notifyAllPresent(event);

        ArgumentCaptor<NotificationLog> logCaptor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getStudent()).isNull();
    }
}
