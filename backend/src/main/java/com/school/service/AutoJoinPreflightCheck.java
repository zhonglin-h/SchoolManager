package com.school.service;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.school.config.AutoJoinProperties;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class AutoJoinPreflightCheck {

    private final AutoJoinProperties autoJoinProperties;
    private final PlaywrightJoinAutomationClient playwrightJoinAutomationClient;

    public AutoJoinPreflightCheck(AutoJoinProperties autoJoinProperties,
                                  PlaywrightJoinAutomationClient playwrightJoinAutomationClient) {
        this.autoJoinProperties = autoJoinProperties;
        this.playwrightJoinAutomationClient = playwrightJoinAutomationClient;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void verify() {
        if (!autoJoinProperties.isEnabled()) {
            return;
        }
        if (!"playwright".equalsIgnoreCase(autoJoinProperties.getProvider())) {
            return;
        }
        if (!autoJoinProperties.isRequirePrincipalProfileSignedIn()) {
            return;
        }
        if (!playwrightJoinAutomationClient.isProfileReady()) {
            log.warn("Auto-join preflight failed: principal Chrome profile is not ready at '{}'",
                    autoJoinProperties.getChromeProfileDir());
        }
    }
}
