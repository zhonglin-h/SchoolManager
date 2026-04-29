package com.school.integration;

import com.school.entity.JoinAttemptStatus;
import com.school.model.CalendarEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("manual")
@SpringBootTest(
        classes = PlaywrightJoinAutomationClientManualSmokeTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(
        locations = {
                "classpath:application-local.properties",
                "classpath:manual-smoke.local.properties"
        }
)
class PlaywrightJoinAutomationClientManualSmokeTest {

    @Autowired
    private PlaywrightJoinAutomationClient client;

    @MockBean
    private JavaMailSender javaMailSender;

    @Value("${smoke.meet.link:}")
    private String meetLinkFromProps;

    @Value("${app.autojoin.chrome-profile-dir:}")
    private String chromeProfileDir;

    @TestConfiguration
    static class TestConfig {
        @Bean
        PlaywrightJoinAutomationClient playwrightJoinAutomationClient() {
            return new PlaywrightJoinAutomationClient();
        }
    }

    @Test
    void attemptJoin_realBrowserSmokeTest() {
        String meetLink = firstNonBlank(normalize(meetLinkFromProps), normalize(System.getenv("SMOKE_MEET_LINK")));
        String effectiveProfileDir = firstNonBlank(normalize(chromeProfileDir), normalize(System.getenv("SMOKE_CHROME_PROFILE_DIR")));

        assumeTrue(meetLink != null && !meetLink.isBlank(),
                "Set SMOKE_MEET_LINK to a real Google Meet URL");
        assumeTrue(effectiveProfileDir != null && !effectiveProfileDir.isBlank(),
                "Set SMOKE_CHROME_PROFILE_DIR to a signed-in Chrome profile directory");

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
