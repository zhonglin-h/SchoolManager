package com.school.service;

import java.util.List;

public record SessionFinalSummarySubject(
        List<String> presentNames,
        List<String> lateNames,
        List<String> absentNames,
        List<String> knownButUnexpectedNames,
        List<String> notInSystemNames
) implements NotificationSubject {
    @Override
    public Long getId() {
        return null;
    }

    @Override
    public String getName() {
        return "final-summary";
    }
}
