package com.school.repository;

import com.school.entity.JoinAttemptLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface JoinAttemptLogRepository extends JpaRepository<JoinAttemptLog, Long> {

    List<JoinAttemptLog> findByAttemptedAtBetweenOrderByAttemptedAtDesc(
            LocalDateTime startInclusive, LocalDateTime endExclusive);
}
