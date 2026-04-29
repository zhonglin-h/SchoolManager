package com.school.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs at startup and logs clear ERROR messages for any missing credential
 * file or blank required property. Prevents cryptic NPEs later.
 *
 * <p>The application continues to run so that non-credential features (e.g.
 * the student registry) still work even when email / Google is not configured.
 */
@Slf4j
@Component
public class StartupValidator {

    @Value("${google.client-secret.path:./client_secret.json}")
    private String clientSecretPath;

    @Value("${spring.mail.username:}")
    private String mailUsername;

    @Value("${spring.mail.password:}")
    private String mailPassword;

    @Value("${google.calendar.id:}")
    private String calendarId;

    @Value("${app.principal.email:}")
    private String principalEmail;

    @Value("${app.autojoin.enabled:false}")
    private boolean autoJoinEnabled;

    @Value("${app.autojoin.chrome-profile-dir:}")
    private String chromeProfileDir;

    @Value("${app.autojoin.chrome-path:}")
    private String chromePath;

    @PostConstruct
    public void validate() {
        List<String> warnings = new ArrayList<>();

        // --- Credential files -------------------------------------------------
        if (!fileExists(clientSecretPath)) {
            warnings.add("Google OAuth credentials file not found: " + clientSecretPath
                    + " — Calendar / Meet features will not work. "
                    + "See credentials-checklist.md for setup instructions.");
        }

        // --- Required properties ----------------------------------------------
        if (isBlank(calendarId)) {
            warnings.add("google.calendar.id is not set — Calendar sync is disabled.");
        }

        if (isBlank(principalEmail)) {
            warnings.add("app.principal.email is not set — principal identity features are disabled.");
        }

        if (isBlank(mailUsername) || isBlank(mailPassword)) {
            warnings.add("spring.mail.username / spring.mail.password are not set — email notifications are disabled.");
        }

        // --- Auto-join --------------------------------------------------------
        if (autoJoinEnabled) {
            if (isBlank(chromeProfileDir)) {
                warnings.add("app.autojoin.enabled=true but app.autojoin.chrome-profile-dir is not set — auto-join will fail.");
            }
            if (isBlank(chromePath) || !fileExists(chromePath)) {
                warnings.add("app.autojoin.enabled=true but app.autojoin.chrome-path is missing or invalid: "
                        + chromePath + " — auto-join will fail.");
            }
        }

        // --- Report -----------------------------------------------------------
        if (warnings.isEmpty()) {
            log.info("StartupValidator: all credential checks passed.");
        } else {
            log.warn("StartupValidator: {} configuration issue(s) detected:", warnings.size());
            for (String w : warnings) {
                log.warn("  [CONFIG] {}", w);
            }
            log.warn("Refer to credentials-checklist.md for setup instructions.");
        }
    }

    private boolean fileExists(String path) {
        return path != null && !path.isBlank() && new File(path).exists();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
