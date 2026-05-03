package com.school.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app.backup")
@Getter
@Setter
public class BackupProperties {

    private boolean enabled = false;
    private String cron = "0 0 2 * * *";
    private String driveFolderId = "";
    private int retentionDays = 30;
    private String pgDumpPath = "pg_dump";
    private Postgres postgres = new Postgres();

    @Getter
    @Setter
    public static class Postgres {
        private String host = "localhost";
        private int port = 5432;
        private String database = "schooldb";
        private String username = "school";
        private String password = "";
    }
}
