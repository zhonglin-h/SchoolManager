package com.school.repository;

import com.school.entity.NotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {
    boolean existsByStudentIdAndCalendarEventIdAndDateAndType(Long studentId, String calendarEventId, LocalDate date, String type);
    boolean existsByCalendarEventIdAndDateAndTypeAndStudentIsNull(String calendarEventId, LocalDate date, String type);
    List<NotificationLog> findAllByOrderBySentAtDesc();
}
