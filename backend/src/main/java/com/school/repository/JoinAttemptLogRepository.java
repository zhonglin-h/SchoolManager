package com.school.repository;

import com.school.entity.JoinAttemptLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface JoinAttemptLogRepository extends JpaRepository<JoinAttemptLog, Long> {

    List<JoinAttemptLog> findByDateOrderByAttemptedAtDesc(LocalDate date);

    Optional<JoinAttemptLog> findByCalendarEventIdAndDateAndTriggerType(
            String calendarEventId, LocalDate date, String triggerType);
}
