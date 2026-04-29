package com.school.integration;

import com.school.entity.JoinAttemptStatus;
import com.school.model.CalendarEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("manual")
class PlaywrightJoinAutomationClientManualSmokeTest {

    @Test
    void attemptJoin_realBrowserSmokeTest() {
        Properties localProps = loadLocalSmokeProperties();
        String meetLink = firstNonBlank(
                normalize(localProps.getProperty("smoke.meet.link")),
                normalize(System.getenv("SMOKE_MEET_LINK"))
        );
        String chromeProfileDir = firstNonBlank(
                normalize(localProps.getProperty("smoke.chrome.profile-dir")),
                normalize(System.getenv("SMOKE_CHROME_PROFILE_DIR"))
        );
        String chromePath = firstNonBlank(
                normalize(localProps.getProperty("smoke.chrome.path")),
                normalize(System.getenv("SMOKE_CHROME_PATH"))
        );

        assumeTrue(meetLink != null && !meetLink.isBlank(),
                "Set SMOKE_MEET_LINK to a real Google Meet URL");
        assumeTrue(chromeProfileDir != null && !chromeProfileDir.isBlank(),
                "Set SMOKE_CHROME_PROFILE_DIR to a signed-in Chrome profile directory");

        PlaywrightJoinAutomationClient client = new PlaywrightJoinAutomationClient();
        ReflectionTestUtils.setField(client, "joinTimeoutSeconds", 45);
        ReflectionTestUtils.setField(client, "maxAttempts", 1);
        ReflectionTestUtils.setField(client, "backoffMs", 1000L);
        ReflectionTestUtils.setField(client, "requireProfileSignedIn", true);
        ReflectionTestUtils.setField(client, "chromeProfileDir", chromeProfileDir);
        ReflectionTestUtils.setField(client, "chromePath", chromePath == null ? "" : chromePath);

        CalendarEvent event = new CalendarEvent(
                "smoke-1",
                "Manual Playwright Smoke",
                meetLink,
                "manual-space-code",
                LocalDateTime.now().plusMinutes(1),
                LocalDateTime.now().plusMinutes(61),
                List.of()
        );

        JoinResult result = client.attemptJoin(event);

        assertThat(result).isNotNull();
        assertThat(result.status())
                .withFailMessage("Expected JOINED but was %s. Detail: %s",
                        result.status(), result.detailMessage())
                .isEqualTo(JoinAttemptStatus.JOINED);
        assertThat(result.detailMessage()).isNotBlank();
    }

    private static Properties loadLocalSmokeProperties() {
        Properties properties = new Properties();
        Path path = Path.of("src", "test", "resources", "manual-smoke.local.properties");
        if (!Files.exists(path)) {
            return properties;
        }
        try {
            for (String rawLine : Files.readAllLines(path)) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                    continue;
                }
                int equalsIdx = line.indexOf('=');
                if (equalsIdx <= 0) {
                    continue;
                }
                String key = line.substring(0, equalsIdx).trim();
                String value = line.substring(equalsIdx + 1).trim();
                properties.setProperty(key, value);
            }
            return properties;
        } catch (IOException ignored) {
            return new Properties();
        }
    }

    private static String firstNonBlank(String first, String fallback) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return fallback;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }
}
