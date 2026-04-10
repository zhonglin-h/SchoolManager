package com.school.config;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.CalendarScopes;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.FileInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

@Configuration
public class GoogleConfig {

    @Value("${google.credentials.path}")
    private String credentialsPath;

    @Bean
    public GoogleCredentials googleCredentials() throws IOException {
        try (FileInputStream stream = new FileInputStream(credentialsPath)) {
            return GoogleCredentials.fromStream(stream)
                    .createScoped(List.of(
                            "https://www.googleapis.com/auth/calendar.readonly",
                            "https://www.googleapis.com/auth/meetings.space.readonly"
                    ));
        }
    }

    @Bean
    public Calendar googleCalendar(GoogleCredentials googleCredentials) throws GeneralSecurityException, IOException {
        return new Calendar.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                JacksonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(googleCredentials)
        ).setApplicationName("SchoolManager").build();
    }
}
