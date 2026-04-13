package com.school.repository;

import com.school.entity.NotificationChannel;
import com.school.entity.NotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {
    boolean existsByStudentIdAndCalendarEventIdAndDateAndTypeAndSuccessTrue(Long studentId, String calendarEventId, LocalDate date, String type);
    boolean existsByCalendarEventIdAndDateAndTypeAndStudentIsNullAndSuccessTrue(String calendarEventId, LocalDate date, String type);
    boolean existsByStudentIdAndCalendarEventIdAndDateAndTypeAndChannelAndSuccessTrue(Long studentId, String calendarEventId, LocalDate date, String type, NotificationChannel channel);
    boolean existsByCalendarEventIdAndDateAndTypeAndChannelAndStudentIsNullAndSuccessTrue(String calendarEventId, LocalDate date, String type, NotificationChannel channel);
    boolean existsByTeacherIdAndCalendarEventIdAndDateAndTypeAndChannelAndSuccessTrue(Long teacherId, String calendarEventId, LocalDate date, String type, NotificationChannel channel);
    List<NotificationLog> findAllByOrderBySentAtDesc();
    void deleteByCalendarEventIdAndDate(String calendarEventId, LocalDate date);
}
