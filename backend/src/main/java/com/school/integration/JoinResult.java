package com.school.integration;

import com.school.entity.JoinAttemptStatus;

/**
 * The outcome of a single auto-join attempt.
 *
 * @param status        terminal status code describing how the attempt ended
 * @param detailMessage human-readable detail for logging and the join attempt log
 */
public record JoinResult(JoinAttemptStatus status, String detailMessage) {}
