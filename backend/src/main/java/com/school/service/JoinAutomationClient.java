package com.school.service;

import com.school.model.CalendarEvent;

public interface JoinAutomationClient {
    JoinAutomationResult attemptJoin(CalendarEvent event);
}
