package com.school.repository;

import com.school.entity.NotificationChannel;
import com.school.entity.NotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {
    boolean existsByPersonIdAndCalendarEventIdAndDateAndTypeAndSuccessTrue(Long personId, String calendarEventId, LocalDate date, String type);
    boolean existsByCalendarEventIdAndDateAndTypeAndPersonIsNullAndSuccessTrue(String calendarEventId, LocalDate date, String type);
    boolean existsByPersonIdAndCalendarEventIdAndDateAndTypeAndChannelAndSuccessTrue(Long personId, String calendarEventId, LocalDate date, String type, NotificationChannel channel);
    boolean existsByCalendarEventIdAndDateAndTypeAndChannelAndPersonIsNullAndSuccessTrue(String calendarEventId, LocalDate date, String type, NotificationChannel channel);
    boolean existsByCalendarEventIdAndDateAndTypeAndChannelAndPersonIsNullAndReasonCodeAndSuccessTrue(
            String calendarEventId, LocalDate date, String type, NotificationChannel channel, String reasonCode);
    List<NotificationLog> findAllByOrderBySentAtDesc();
    void deleteByCalendarEventIdAndDate(String calendarEventId, LocalDate date);
}
