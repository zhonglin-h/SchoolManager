package com.school.integration;

import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventAttendee;
import com.google.api.services.calendar.model.EventDateTime;
import com.school.model.CalendarEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
public class GoogleCalendarClient {

    private final Calendar calendar;

    @Value("${google.calendar.id}")
    private String calendarId;

    public GoogleCalendarClient(Calendar calendar) {
        this.calendar = calendar;
    }

    public List<CalendarEvent> getTodaysEvents() throws IOException {
        ZoneId localZone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(localZone);
        ZonedDateTime startOfDay = today.atStartOfDay(localZone);
        ZonedDateTime endOfDay = today.plusDays(1).atStartOfDay(localZone);

        DateTime timeMin = new DateTime(startOfDay.toInstant().toEpochMilli());
        DateTime timeMax = new DateTime(endOfDay.toInstant().toEpochMilli());

        List<Event> events = calendar.events().list(calendarId)
                .setTimeMin(timeMin)
                .setTimeMax(timeMax)
                .setSingleEvents(true)
                .setOrderBy("startTime")
                .execute()
                .getItems();

        List<CalendarEvent> result = new ArrayList<>();
        for (Event event : events) {
            EventDateTime startEdt = event.getStart();
            if (startEdt.getDateTime() == null) {
                continue;
            }

            String meetLink = null;
            String spaceCode = null;
            if (event.getConferenceData() != null && event.getConferenceData().getEntryPoints() != null) {
                for (var entryPoint : event.getConferenceData().getEntryPoints()) {
                    if ("video".equals(entryPoint.getEntryPointType())) {
                        meetLink = entryPoint.getUri();
                        if (meetLink != null) {
                            String[] parts = meetLink.split("/");
                            spaceCode = parts[parts.length - 1];
                        }
                        break;
                    }
                }
            }

            if (meetLink == null) {
                continue;
            }

            LocalDateTime startTime = LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(startEdt.getDateTime().getValue()),
                    ZoneId.systemDefault()
            );
            LocalDateTime endTime = LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(event.getEnd().getDateTime().getValue()),
                    ZoneId.systemDefault()
            );

            List<String> attendeeEmails = new ArrayList<>();
            if (event.getAttendees() != null) {
                for (EventAttendee attendee : event.getAttendees()) {
                    if (attendee.getEmail() != null) {
                        attendeeEmails.add(attendee.getEmail());
                    }
                }
            }

            result.add(new CalendarEvent(
                    event.getId(),
                    event.getSummary(),
                    meetLink,
                    spaceCode,
                    startTime,
                    endTime,
                    attendeeEmails
            ));
        }

        return result;
    }
}
