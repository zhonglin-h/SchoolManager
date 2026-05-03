package com.school.config;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.CalendarScopes;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

@Configuration
public class GoogleConfig {

    @Value("${google.client-secret.path}")
    private String clientSecretPath;

    @Value("${google.tokens.dir:./data/tokens}")
    private String tokensDir;

    private static final List<String> SCOPES = List.of(
            CalendarScopes.CALENDAR_READONLY,
            "https://www.googleapis.com/auth/meetings.space.readonly",
            DriveScopes.DRIVE_FILE
    );

    @Bean
    public NetHttpTransport httpTransport() throws GeneralSecurityException, IOException {
        return GoogleNetHttpTransport.newTrustedTransport();
    }

    @Bean
    public Credential googleCredential(NetHttpTransport httpTransport) throws IOException, GeneralSecurityException {
        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(
                GsonFactory.getDefaultInstance(),
                new FileReader(clientSecretPath));

        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                httpTransport,
                GsonFactory.getDefaultInstance(),
                clientSecrets,
                SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(new File(tokensDir)))
                .setAccessType("offline")
                .build();

        // On first run: opens browser for one-time login.
        // On subsequent runs: silently loads stored token from tokensDir.
        return new AuthorizationCodeInstalledApp(flow, new LocalServerReceiver()).authorize("user");
    }

    @Bean
    public Calendar googleCalendar(NetHttpTransport httpTransport, Credential credential) throws GeneralSecurityException, IOException {
        return new Calendar.Builder(httpTransport, GsonFactory.getDefaultInstance(), credential)
                .setApplicationName("SchoolManager")
                .build();
    }

    @Bean
    public Drive googleDrive(NetHttpTransport httpTransport, Credential credential) {
        return new Drive.Builder(httpTransport, GsonFactory.getDefaultInstance(), credential)
                .setApplicationName("SchoolManager")
                .build();
    }
}
