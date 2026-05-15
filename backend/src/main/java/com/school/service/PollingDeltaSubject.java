package com.school.service;

import java.util.List;

public record PollingDeltaSubject(
        List<String> arrivedNames,
        List<String> lateArrivedNames,
        List<String> newlyMissingNames,
        List<String> noLongerMissingNames,
        List<String> knownButUnexpectedNames,
        List<String> notInSystemNames
) implements NotificationSubject {
    @Override
    public Long getId() {
        return null;
    }

    @Override
    public String getName() {
        return "live-delta";
    }
}
