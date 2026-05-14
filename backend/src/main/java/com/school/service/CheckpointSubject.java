package com.school.service;

import java.util.List;

public record CheckpointSubject(
        String checkLabel,
        List<String> arrivedNames,
        List<String> notArrivedNames
) implements NotificationSubject {
    @Override public Long getId() { return null; }
    @Override public String getName() { return checkLabel; }
}
