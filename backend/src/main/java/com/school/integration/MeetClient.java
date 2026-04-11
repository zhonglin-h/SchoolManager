package com.school.integration;

import java.io.IOException;
import java.util.List;

public interface MeetClient {

    boolean isMeetingActive(String spaceCode) throws IOException, InterruptedException;

    /** Returns only participants currently in the meeting (no latestEndTime). */
    List<MeetParticipant> getActiveParticipants(String spaceCode) throws IOException, InterruptedException;

    /** Returns all participants who joined, including those who have left. */
    List<MeetParticipant> getAllParticipants(String spaceCode) throws IOException, InterruptedException;
}
