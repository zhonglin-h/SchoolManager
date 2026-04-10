package com.school.service;

import com.school.integration.GoogleCalendarClient;
import com.school.model.CalendarEvent;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

@Service
public class CalendarSyncService {

    private final GoogleCalendarClient googleCalendarClient;

    public CalendarSyncService(GoogleCalendarClient googleCalendarClient) {
        this.googleCalendarClient = googleCalendarClient;
    }

    public List<CalendarEvent> getTodaysEvents() throws IOException {
        return googleCalendarClient.getTodaysEvents();
    }
}
