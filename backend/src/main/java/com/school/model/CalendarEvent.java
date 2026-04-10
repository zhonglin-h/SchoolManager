package com.school.model;

import java.time.LocalDateTime;
import java.util.List;

public class CalendarEvent {
    private String id;
    private String title;
    private String meetLink;
    private String spaceCode;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private List<String> attendeeEmails;

    public CalendarEvent() {}

    public CalendarEvent(String id, String title, String meetLink, String spaceCode,
                         LocalDateTime startTime, LocalDateTime endTime, List<String> attendeeEmails) {
        this.id = id;
        this.title = title;
        this.meetLink = meetLink;
        this.spaceCode = spaceCode;
        this.startTime = startTime;
        this.endTime = endTime;
        this.attendeeEmails = attendeeEmails;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getMeetLink() { return meetLink; }
    public void setMeetLink(String meetLink) { this.meetLink = meetLink; }

    public String getSpaceCode() { return spaceCode; }
    public void setSpaceCode(String spaceCode) { this.spaceCode = spaceCode; }

    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }

    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }

    public List<String> getAttendeeEmails() { return attendeeEmails; }
    public void setAttendeeEmails(List<String> attendeeEmails) { this.attendeeEmails = attendeeEmails; }
}
