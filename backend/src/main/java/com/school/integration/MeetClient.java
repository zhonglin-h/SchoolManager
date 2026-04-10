package com.school.integration;

import java.io.IOException;
import java.util.List;

public interface MeetClient {

    boolean isMeetingActive(String spaceCode) throws IOException, InterruptedException;

    List<MeetParticipant> getActiveParticipants(String spaceCode) throws IOException, InterruptedException;
}
