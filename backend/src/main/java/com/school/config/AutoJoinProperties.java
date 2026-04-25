package com.school.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@Data
@Component
@ConfigurationProperties(prefix = "app.autojoin")
public class AutoJoinProperties {
    private boolean enabled = false;
    private String provider = "noop";
    private int triggerOffsetSeconds = 60;
    private int joinTimeoutSeconds = 30;
    private String chromeProfileDir = "";
    private String chromePath = "";
    private int retryMaxAttempts = 2;
    private int retryBackoffMs = 500;
    private boolean notifyOnSuccess = false;
    private boolean requirePrincipalProfileSignedIn = true;
    private boolean skipIfMeetingActive = false;
}
