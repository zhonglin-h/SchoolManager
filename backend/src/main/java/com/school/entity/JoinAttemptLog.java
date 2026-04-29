package com.school.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "join_attempt_log")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JoinAttemptLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "calendar_event_id", nullable = false)
    private String calendarEventId;

    @Column(name = "scheduled_start")
    private LocalDateTime scheduledStart;

    @Column(name = "attempted_at", nullable = false)
    private LocalDateTime attemptedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JoinAttemptStatus status;

    @Column(name = "detail_message", length = 1000)
    private String detailMessage;

    @Column(name = "trigger_type", nullable = false)
    private String triggerType;
}
