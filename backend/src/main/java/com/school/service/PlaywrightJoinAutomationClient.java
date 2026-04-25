package com.school.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.school.config.AutoJoinProperties;
import com.school.integration.MeetClient;
import com.school.model.CalendarEvent;

@Component
public class PlaywrightJoinAutomationClient implements JoinAutomationClient {

    private static final String WINDOWS_DEFAULT_CHROME_PATH = "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe";
    private final AutoJoinProperties autoJoinProperties;
    private final MeetClient meetClient;

    public PlaywrightJoinAutomationClient(AutoJoinProperties autoJoinProperties, MeetClient meetClient) {
        this.autoJoinProperties = autoJoinProperties;
        this.meetClient = meetClient;
    }

    @Override
    public JoinAutomationResult attemptJoin(CalendarEvent event) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) {
            return new JoinAutomationResult(
                    JoinAttemptStatus.FAILED_PERMISSION,
                    "unsupported_os",
                    "Auto-join browser automation is currently supported on Windows only.");
        }

        String meetLink = event.getMeetLink();
        if (meetLink == null || meetLink.isBlank() || event.getSpaceCode() == null || event.getSpaceCode().isBlank()) {
            return new JoinAutomationResult(
                    JoinAttemptStatus.FAILED_UI_NOT_FOUND,
                    "missing_meet_link_or_space",
                    "Meet URL or space code is missing for this event.");
        }

        if (autoJoinProperties.isRequirePrincipalProfileSignedIn() && !isProfileReady()) {
            return new JoinAutomationResult(
                    JoinAttemptStatus.FAILED_AUTH,
                    "principal_profile_not_signed_in",
                    "Chrome profile directory is missing or not initialized.");
        }

        try {
            launchChrome(meetLink);
        } catch (IOException e) {
            return new JoinAutomationResult(
                    JoinAttemptStatus.FAILED_NETWORK,
                    "chrome_launch_failed",
                    e.getMessage());
        }

        Instant timeoutAt = Instant.now().plusSeconds(Math.max(1, autoJoinProperties.getJoinTimeoutSeconds()));
        while (Instant.now().isBefore(timeoutAt)) {
            try {
                if (meetClient.isMeetingActive(event.getSpaceCode())) {
                    return new JoinAutomationResult(
                            JoinAttemptStatus.JOINED,
                            "joined",
                            "Meeting became active after automated browser launch.");
                }
            } catch (Exception e) {
                return new JoinAutomationResult(
                        JoinAttemptStatus.FAILED_NETWORK,
                        "meeting_status_check_failed",
                        e.getMessage());
            }

            try {
                Thread.sleep(Duration.ofSeconds(2));
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return new JoinAutomationResult(
                        JoinAttemptStatus.FAILED_UNKNOWN,
                        "interrupted",
                        "Join polling was interrupted.");
            }
        }

        return new JoinAutomationResult(
                JoinAttemptStatus.FAILED_WAITING_ROOM_TIMEOUT,
                "waiting_room_timeout",
                "Timed out waiting for meeting activation after browser launch.");
    }

    private void launchChrome(String meetLink) throws IOException {
        String chromePath = autoJoinProperties.getChromePath();
        if (chromePath == null || chromePath.isBlank()) {
            chromePath = WINDOWS_DEFAULT_CHROME_PATH;
        }

        List<String> command = new ArrayList<>();
        command.add(chromePath);
        if (autoJoinProperties.getChromeProfileDir() != null && !autoJoinProperties.getChromeProfileDir().isBlank()) {
            command.add("--user-data-dir=" + autoJoinProperties.getChromeProfileDir());
        }
        command.add("--new-window");
        command.add(meetLink);

        new ProcessBuilder(command).start();
    }

    public boolean isProfileReady() {
        String profileDir = autoJoinProperties.getChromeProfileDir();
        if (profileDir == null || profileDir.isBlank()) {
            return false;
        }
        Path path = Path.of(profileDir);
        return Files.isDirectory(path) && Files.exists(path.resolve("Default"));
    }
}
