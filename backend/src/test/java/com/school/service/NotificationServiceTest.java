package com.school.service;

import com.school.entity.NotificationChannel;
import com.school.entity.NotificationLog;
import com.school.entity.Person;
import com.school.entity.PersonType;
import com.school.integration.EmailClient;
import com.school.integration.TelegramClient;
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
import static org.mockito.ArgumentMatchers.contains;
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

    @Mock
    TelegramClient telegramClient;

    private static final String PRINCIPAL = "principal@test.com";
    private static final String TELEGRAM_CHAT_ID = "test-chat-id";

    NotificationService notificationService;

    private CalendarEvent event;
    private Person student;

    @BeforeEach
    void setUp() {
        notificationService = new NotificationService(notificationLogRepository, emailClient, telegramClient, PRINCIPAL, true, true, TELEGRAM_CHAT_ID);

        event = new CalendarEvent("evt-1", "Math Class", "https://meet.google.com/abc",
                "abc", LocalDateTime.now(), LocalDateTime.now().plusHours(1), List.of());

        student = Person.builder()
                .id(1L).name("Alice").meetEmail("alice@meet.com")
                .personType(PersonType.STUDENT)
                .classroomEmail("alice@class.com")
                .parentEmail("alice-parent@test.com").parentPhone("555-1111")
                .build();
    }

    @Test
    void clearTodayLogsForEvent_delegatesToRepository() {
        notificationService.clearTodayLogsForEvent("evt-1");
        verify(notificationLogRepository).deleteByCalendarEventIdAndDate("evt-1", LocalDate.now());
    }

    // --- MEETING_NOT_STARTED_15 (email to principal + Telegram) ---

    @Test
    void notifyMeetingNotStarted_sendsEmailAndTelegramAndSavesLogs() {
        when(notificationLogRepository.existsByCalendarEventIdAndDateAndTypeAndChannelAndPersonIsNullAndSuccessTrue(
                anyString(), any(LocalDate.class), anyString(), eq(NotificationChannel.EMAIL))).thenReturn(false);
        when(notificationLogRepository.existsByCalendarEventIdAndDateAndTypeAndChannelAndPersonIsNullAndSuccessTrue(
                anyString(), any(LocalDate.class), anyString(), eq(NotificationChannel.TELEGRAM))).thenReturn(false);

        notificationService.notify(NotificationType.MEETING_NOT_STARTED_15, event, null);

        verify(emailClient).send(eq(PRINCIPAL), anyString(), anyString());
        verify(telegramClient).send(anyString());
        verify(notificationLogRepository, times(2)).save(any(NotificationLog.class));
    }

    @Test
    void notifyMeetingNotStarted_skipsDuplicate() {
        when(notificationLogRepository.existsByCalendarEventIdAndDateAndTypeAndChannelAndPersonIsNullAndSuccessTrue(
                anyString(), any(LocalDate.class), anyString(), eq(NotificationChannel.EMAIL))).thenReturn(true);
        when(notificationLogRepository.existsByCalendarEventIdAndDateAndTypeAndChannelAndPersonIsNullAndSuccessTrue(
                anyString(), any(LocalDate.class), anyString(), eq(NotificationChannel.TELEGRAM))).thenReturn(true);

        notificationService.notify(NotificationType.MEETING_NOT_STARTED_15, event, null);

        verify(emailClient, never()).send(anyString(), anyString(), anyString());
        verify(telegramClient, never()).send(anyString());
        verify(notificationLogRepository, never()).save(any());
    }

    // --- NOT_YET_JOINED_3 (email to principal + parent, Telegram) ---

    @Test
    void notifyNotYetJoined_sendsToPrincipalAndParentViaEmailAndTelegram() {
        when(notificationLogRepository.existsByPersonIdAndCalendarEventIdAndDateAndTypeAndChannelAndSuccessTrue(
                anyLong(), anyString(), any(LocalDate.class), anyString(), eq(NotificationChannel.EMAIL))).thenReturn(false);
        when(notificationLogRepository.existsByPersonIdAndCalendarEventIdAndDateAndTypeAndChannelAndSuccessTrue(
                anyLong(), anyString(), any(LocalDate.class), anyString(), eq(NotificationChannel.TELEGRAM))).thenReturn(false);

        notificationService.notify(NotificationType.NOT_YET_JOINED, event, new PersonSubject(student));

        ArgumentCaptor<String> toCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailClient, times(2)).send(toCaptor.capture(), anyString(), anyString());
        assertThat(toCaptor.getAllValues()).containsExactlyInAnyOrder(PRINCIPAL, "alice-parent@test.com");
        verify(telegramClient).send(anyString());
        verify(notificationLogRepository, times(2)).save(any(NotificationLog.class));
    }

    @Test
    void notifyNotYetJoined_skipsDuplicate() {
        when(notificationLogRepository.existsByPersonIdAndCalendarEventIdAndDateAndTypeAndChannelAndSuccessTrue(
                anyLong(), anyString(), any(LocalDate.class), anyString(), eq(NotificationChannel.EMAIL))).thenReturn(true);
        when(notificationLogRepository.existsByPersonIdAndCalendarEventIdAndDateAndTypeAndChannelAndSuccessTrue(
                anyLong(), anyString(), any(LocalDate.class), anyString(), eq(NotificationChannel.TELEGRAM))).thenReturn(true);

        notificationService.notify(NotificationType.NOT_YET_JOINED, event, new PersonSubject(student));

        verify(emailClient, never()).send(anyString(), anyString(), anyString());
        verify(telegramClient, never()).send(anyString());
    }

    // --- ARRIVAL (Telegram to principal, no email) ---

    @Test
    void notifyArrival_sendsTelegramNotEmail() {
        when(notificationLogRepository.existsByPersonIdAndCalendarEventIdAndDateAndTypeAndChannelAndSuccessTrue(
                anyLong(), anyString(), any(LocalDate.class), anyString(), eq(NotificationChannel.TELEGRAM))).thenReturn(false);

        notificationService.notify(NotificationType.ARRIVAL, event, new PersonSubject(student));

        verify(telegramClient).send(anyString());
        verify(emailClient, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void notifyArrival_skipsDuplicate() {
        when(notificationLogRepository.existsByPersonIdAndCalendarEventIdAndDateAndTypeAndChannelAndSuccessTrue(
                anyLong(), anyString(), any(LocalDate.class), anyString(), eq(NotificationChannel.TELEGRAM))).thenReturn(true);

        notificationService.notify(NotificationType.ARRIVAL, event, new PersonSubject(student));

        verify(telegramClient, never()).send(anyString());
        verify(emailClient, never()).send(anyString(), anyString(), anyString());
    }

    // --- ALL_PRESENT (Telegram to principal, no email, null student) ---

    @Test
    void notifyAllPresent_sendsTelegramNotEmail() {
        when(notificationLogRepository.existsByCalendarEventIdAndDateAndTypeAndChannelAndPersonIsNullAndSuccessTrue(
                anyString(), any(LocalDate.class), anyString(), eq(NotificationChannel.TELEGRAM))).thenReturn(false);

        notificationService.notify(NotificationType.ALL_PRESENT, event, null);

        verify(telegramClient).send(anyString());
        verify(emailClient, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void notifyAllPresent_skipsDuplicate() {
        when(notificationLogRepository.existsByCalendarEventIdAndDateAndTypeAndChannelAndPersonIsNullAndSuccessTrue(
                anyString(), any(LocalDate.class), anyString(), eq(NotificationChannel.TELEGRAM))).thenReturn(true);

        notificationService.notify(NotificationType.ALL_PRESENT, event, null);

        verify(telegramClient, never()).send(anyString());
        verify(emailClient, never()).send(anyString(), anyString(), anyString());
    }

    // --- LATE (Telegram to principal, email to parent) ---

    @Test
    void notifyLate_sendsTelegramToPrincipalAndEmailToParent() {
        when(notificationLogRepository.existsByPersonIdAndCalendarEventIdAndDateAndTypeAndChannelAndSuccessTrue(
                anyLong(), anyString(), any(LocalDate.class), anyString(), eq(NotificationChannel.EMAIL))).thenReturn(false);
        when(notificationLogRepository.existsByPersonIdAndCalendarEventIdAndDateAndTypeAndChannelAndSuccessTrue(
                anyLong(), anyString(), any(LocalDate.class), anyString(), eq(NotificationChannel.TELEGRAM))).thenReturn(false);

        notificationService.notify(NotificationType.LATE, event, new PersonSubject(student));

        verify(telegramClient).send(anyString());
        ArgumentCaptor<String> toCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailClient, times(1)).send(toCaptor.capture(), anyString(), anyString());
        assertThat(toCaptor.getValue()).isEqualTo("alice-parent@test.com");
        verify(notificationLogRepository, times(2)).save(any(NotificationLog.class));
    }

    @Test
    void notifyLate_skipsDuplicate() {
        when(notificationLogRepository.existsByPersonIdAndCalendarEventIdAndDateAndTypeAndChannelAndSuccessTrue(
                anyLong(), anyString(), any(LocalDate.class), anyString(), eq(NotificationChannel.EMAIL))).thenReturn(true);
        when(notificationLogRepository.existsByPersonIdAndCalendarEventIdAndDateAndTypeAndChannelAndSuccessTrue(
                anyLong(), anyString(), any(LocalDate.class), anyString(), eq(NotificationChannel.TELEGRAM))).thenReturn(true);

        notificationService.notify(NotificationType.LATE, event, new PersonSubject(student));

        verify(emailClient, never()).send(anyString(), anyString(), anyString());
        verify(telegramClient, never()).send(anyString());
    }

    // --- ABSENT (Telegram to principal, email to parent) ---

    @Test
    void notifyAbsent_sendsTelegramToPrincipalAndEmailToParent() {
        when(notificationLogRepository.existsByPersonIdAndCalendarEventIdAndDateAndTypeAndChannelAndSuccessTrue(
                anyLong(), anyString(), any(LocalDate.class), anyString(), eq(NotificationChannel.EMAIL))).thenReturn(false);
        when(notificationLogRepository.existsByPersonIdAndCalendarEventIdAndDateAndTypeAndChannelAndSuccessTrue(
                anyLong(), anyString(), any(LocalDate.class), anyString(), eq(NotificationChannel.TELEGRAM))).thenReturn(false);

        notificationService.notify(NotificationType.ABSENT, event, new PersonSubject(student));

        verify(telegramClient).send(anyString());
        ArgumentCaptor<String> toCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailClient, times(1)).send(toCaptor.capture(), anyString(), anyString());
        assertThat(toCaptor.getValue()).isEqualTo("alice-parent@test.com");
        verify(notificationLogRepository, times(2)).save(any(NotificationLog.class));
    }

    @Test
    void notifyAbsent_skipsDuplicate() {
        when(notificationLogRepository.existsByPersonIdAndCalendarEventIdAndDateAndTypeAndChannelAndSuccessTrue(
                anyLong(), anyString(), any(LocalDate.class), anyString(), eq(NotificationChannel.EMAIL))).thenReturn(true);
        when(notificationLogRepository.existsByPersonIdAndCalendarEventIdAndDateAndTypeAndChannelAndSuccessTrue(
                anyLong(), anyString(), any(LocalDate.class), anyString(), eq(NotificationChannel.TELEGRAM))).thenReturn(true);

        notificationService.notify(NotificationType.ABSENT, event, new PersonSubject(student));

        verify(emailClient, never()).send(anyString(), anyString(), anyString());
        verify(telegramClient, never()).send(anyString());
    }

    // --- Per-channel dedup: Telegram log doesn't block email and vice versa ---

    @Test
    void perChannelDedup_telegramLogDoesNotBlockEmail() {
        // Telegram already sent, but email has not been sent yet
        when(notificationLogRepository.existsByPersonIdAndCalendarEventIdAndDateAndTypeAndChannelAndSuccessTrue(
                anyLong(), anyString(), any(LocalDate.class), anyString(), eq(NotificationChannel.EMAIL))).thenReturn(false);
        when(notificationLogRepository.existsByPersonIdAndCalendarEventIdAndDateAndTypeAndChannelAndSuccessTrue(
                anyLong(), anyString(), any(LocalDate.class), anyString(), eq(NotificationChannel.TELEGRAM))).thenReturn(true);

        notificationService.notify(NotificationType.ABSENT, event, new PersonSubject(student));

        // Email path still proceeds
        verify(emailClient, times(1)).send(eq("alice-parent@test.com"), anyString(), anyString());
        // Telegram is skipped due to dedup
        verify(telegramClient, never()).send(anyString());
    }

    @Test
    void perChannelDedup_emailLogDoesNotBlockTelegram() {
        // Email already sent, but Telegram has not been sent yet
        when(notificationLogRepository.existsByPersonIdAndCalendarEventIdAndDateAndTypeAndChannelAndSuccessTrue(
                anyLong(), anyString(), any(LocalDate.class), anyString(), eq(NotificationChannel.EMAIL))).thenReturn(true);
        when(notificationLogRepository.existsByPersonIdAndCalendarEventIdAndDateAndTypeAndChannelAndSuccessTrue(
                anyLong(), anyString(), any(LocalDate.class), anyString(), eq(NotificationChannel.TELEGRAM))).thenReturn(false);

        notificationService.notify(NotificationType.ABSENT, event, new PersonSubject(student));

        // Telegram path still proceeds
        verify(telegramClient).send(anyString());
        // Email is skipped due to dedup
        verify(emailClient, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void notifyUnmatchedGuests_sendsEachTimeWithoutDedup() {
        GuestSubject guestRecipient = new GuestSubject(
                List.of("unknown@meet.com", "external@meet.com"),
                List.of("Mystery Person"));

        notificationService.notify(NotificationType.UNMATCHED_GUESTS, event, guestRecipient);
        notificationService.notify(NotificationType.UNMATCHED_GUESTS, event, guestRecipient);

        verify(notificationLogRepository, never())
                .existsByCalendarEventIdAndDateAndTypeAndChannelAndPersonIsNullAndSuccessTrue(
                        anyString(), any(LocalDate.class), anyString(), any());
        verify(emailClient, times(2)).send(eq(PRINCIPAL), eq("Unknown People in Session: Math Class"),
                contains("unknown@meet.com"));
        verify(telegramClient, times(2)).send(contains("In room but not found in system: Mystery Person"));
        verify(notificationLogRepository, times(4)).save(any(NotificationLog.class));
    }

    // --- Telegram failure: logged with success=false, does not prevent email ---

    @Test
    void telegramFailure_loggedAsFailureAndEmailStillSent() {
        when(notificationLogRepository.existsByPersonIdAndCalendarEventIdAndDateAndTypeAndChannelAndSuccessTrue(
                anyLong(), anyString(), any(LocalDate.class), anyString(), eq(NotificationChannel.EMAIL))).thenReturn(false);
        when(notificationLogRepository.existsByPersonIdAndCalendarEventIdAndDateAndTypeAndChannelAndSuccessTrue(
                anyLong(), anyString(), any(LocalDate.class), anyString(), eq(NotificationChannel.TELEGRAM))).thenReturn(false);
        org.mockito.Mockito.doThrow(new RuntimeException("network error")).when(telegramClient).send(anyString());

        notificationService.notify(NotificationType.ABSENT, event, new PersonSubject(student));

        // Email to parent still sent
        verify(emailClient).send(eq("alice-parent@test.com"), anyString(), anyString());

        // Two logs: one email, one failed Telegram
        ArgumentCaptor<NotificationLog> logCaptor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepository, times(2)).save(logCaptor.capture());
        List<NotificationLog> logs = logCaptor.getAllValues();

        NotificationLog telegramLog = logs.stream()
                .filter(l -> l.getChannel() == NotificationChannel.TELEGRAM)
                .findFirst().orElseThrow();
        assertThat(telegramLog.isSuccess()).isFalse();
        assertThat(telegramLog.getFailureReason()).isEqualTo("network error");
    }

    // --- log content ---

    @Test
    void savedTelegramLog_hasCorrectFields() {
        when(notificationLogRepository.existsByPersonIdAndCalendarEventIdAndDateAndTypeAndChannelAndSuccessTrue(
                anyLong(), anyString(), any(LocalDate.class), anyString(), eq(NotificationChannel.TELEGRAM))).thenReturn(false);

        notificationService.notify(NotificationType.ARRIVAL, event, new PersonSubject(student));

        ArgumentCaptor<NotificationLog> logCaptor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepository).save(logCaptor.capture());
        NotificationLog log = logCaptor.getValue();
        assertThat(log.getCalendarEventId()).isEqualTo("evt-1");
        assertThat(log.getType()).isEqualTo("ARRIVAL");
        assertThat(log.getPerson()).isEqualTo(student);
        assertThat(log.getDate()).isEqualTo(LocalDate.now());
        assertThat(log.getChannel()).isEqualTo(NotificationChannel.TELEGRAM);
        assertThat(log.isSuccess()).isTrue();
        assertThat(log.getRecipient()).isEqualTo(TELEGRAM_CHAT_ID);
    }

    @Test
    void savedEmailLog_recipientContainsPrincipalAndParent() {
        when(notificationLogRepository.existsByPersonIdAndCalendarEventIdAndDateAndTypeAndChannelAndSuccessTrue(
                anyLong(), anyString(), any(LocalDate.class), anyString(), eq(NotificationChannel.EMAIL))).thenReturn(false);
        when(notificationLogRepository.existsByPersonIdAndCalendarEventIdAndDateAndTypeAndChannelAndSuccessTrue(
                anyLong(), anyString(), any(LocalDate.class), anyString(), eq(NotificationChannel.TELEGRAM))).thenReturn(false);

        notificationService.notify(NotificationType.NOT_YET_JOINED, event, new PersonSubject(student));

        ArgumentCaptor<NotificationLog> logCaptor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepository, times(2)).save(logCaptor.capture());
        NotificationLog emailLog = logCaptor.getAllValues().stream()
                .filter(l -> l.getChannel() == NotificationChannel.EMAIL)
                .findFirst().orElseThrow();
        assertThat(emailLog.getRecipient())
                .isEqualTo(PRINCIPAL + ", " + "alice-parent@test.com");
    }

    @Test
    void savedTelegramLog_recipientIsTheChatId() {
        when(notificationLogRepository.existsByCalendarEventIdAndDateAndTypeAndChannelAndPersonIsNullAndSuccessTrue(
                anyString(), any(LocalDate.class), anyString(), eq(NotificationChannel.TELEGRAM))).thenReturn(false);

        notificationService.notify(NotificationType.ALL_PRESENT, event, null);

        ArgumentCaptor<NotificationLog> logCaptor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getRecipient()).isEqualTo(TELEGRAM_CHAT_ID);
    }

    @Test
    void principalOnlyTelegramLog_hasNullStudent() {
        when(notificationLogRepository.existsByCalendarEventIdAndDateAndTypeAndChannelAndPersonIsNullAndSuccessTrue(
                anyString(), any(LocalDate.class), anyString(), eq(NotificationChannel.TELEGRAM))).thenReturn(false);

        notificationService.notify(NotificationType.ALL_PRESENT, event, null);

        ArgumentCaptor<NotificationLog> logCaptor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getPerson()).isNull();
    }
}
