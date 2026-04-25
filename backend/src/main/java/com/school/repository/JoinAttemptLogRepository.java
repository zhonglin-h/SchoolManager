package com.school.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.school.entity.JoinAttemptLog;

public interface JoinAttemptLogRepository extends JpaRepository<JoinAttemptLog, Long> {
    boolean existsByCalendarEventIdAndDateAndTriggerType(String calendarEventId, LocalDate date, String triggerType);
    Optional<JoinAttemptLog> findTopByCalendarEventIdAndDateAndTriggerTypeOrderByAttemptedAtDesc(
            String calendarEventId, LocalDate date, String triggerType);
    List<JoinAttemptLog> findByDateOrderByAttemptedAtDesc(LocalDate date);
}
