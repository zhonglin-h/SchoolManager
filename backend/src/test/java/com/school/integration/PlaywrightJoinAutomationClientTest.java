package com.school.integration;

import com.school.entity.JoinAttemptStatus;
import com.school.model.CalendarEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlaywrightJoinAutomationClientTest {

    private PlaywrightJoinAutomationClient client;
    private CalendarEvent event;

    @BeforeEach
    void setUp() {
        client = new PlaywrightJoinAutomationClient();
        ReflectionTestUtils.setField(client, "joinTimeoutSeconds", 30);
        ReflectionTestUtils.setField(client, "maxAttempts", 1);
        ReflectionTestUtils.setField(client, "backoffMs", 1L);
        ReflectionTestUtils.setField(client, "requireProfileSignedIn", true);
        ReflectionTestUtils.setField(client, "chromeProfileDir", "C:/Users/principal/AppData/Local/ChromeMeetProfile");
        ReflectionTestUtils.setField(client, "chromePath", "");

        event = new CalendarEvent(
                "evt-1",
                "Math Class",
                "https://meet.google.com/abc-defg-hij",
                "abc-defg-hij",
                LocalDateTime.now().plusMinutes(1),
                LocalDateTime.now().plusMinutes(61),
                List.of()
        );
    }

    @Test
    void classifyErrorMessage_mapsNetwork() {
        assertThat(PlaywrightJoinAutomationClient.classifyErrorMessage("net::ERR_INTERNET_DISCONNECTED"))
                .isEqualTo(JoinAttemptStatus.FAILED_NETWORK);
    }

    @Test
    void classifyErrorMessage_mapsAuth() {
        assertThat(PlaywrightJoinAutomationClient.classifyErrorMessage("Choose an account to sign in"))
                .isEqualTo(JoinAttemptStatus.FAILED_AUTH);
    }

    @Test
    void classifyErrorMessage_mapsPermission() {
        assertThat(PlaywrightJoinAutomationClient.classifyErrorMessage("Permission denied by host"))
                .isEqualTo(JoinAttemptStatus.FAILED_PERMISSION);
    }

    @Test
    void classifyErrorMessage_mapsUiNotFound() {
        assertThat(PlaywrightJoinAutomationClient.classifyErrorMessage("selector for Join now not found"))
                .isEqualTo(JoinAttemptStatus.FAILED_UI_NOT_FOUND);
    }

    @Test
    void attemptJoin_returnsFailedAuthWhenSignedInProfileRequiredAndMissing() {
        ReflectionTestUtils.setField(client, "chromeProfileDir", "");

        JoinResult result = client.attemptJoin(event);

        assertThat(result.status()).isEqualTo(JoinAttemptStatus.FAILED_AUTH);
        assertThat(result.detailMessage()).contains("chrome-profile-dir");
    }

    @Test
    void attemptJoin_returnsFailedUnknownWhenMeetLinkMissing() {
        event.setMeetLink("  ");

        JoinResult result = client.attemptJoin(event);

        assertThat(result.status()).isEqualTo(JoinAttemptStatus.FAILED_UNKNOWN);
        assertThat(result.detailMessage()).contains("no Meet link");
    }

    @Test
    void attemptJoin_classifiesFactoryFailureAsNetwork() {
        client.setPlaywrightFactory(() -> {
            throw new RuntimeException("timeout while opening browser");
        });

        JoinResult result = client.attemptJoin(event);

        assertThat(result.status()).isEqualTo(JoinAttemptStatus.FAILED_NETWORK);
    }
}
