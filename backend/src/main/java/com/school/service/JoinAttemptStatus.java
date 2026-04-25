package com.school.service;

public enum JoinAttemptStatus {
    JOINED,
    FAILED_AUTH,
    FAILED_PERMISSION,
    FAILED_WAITING_ROOM_TIMEOUT,
    FAILED_UI_NOT_FOUND,
    FAILED_NETWORK,
    FAILED_UNKNOWN
}
