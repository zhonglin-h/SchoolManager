package com.school.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.auth.oauth2.Credential;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(name = "app.meet.mock", havingValue = "false", matchIfMissing = true)
public class GoogleMeetClient implements MeetClient {

    private final Credential credential;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public GoogleMeetClient(Credential credential) {
        this.credential = credential;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    private String getValidAccessToken() throws IOException {
        Long expiresIn = credential.getExpiresInSeconds();
        if (expiresIn == null || expiresIn <= 60) {
            credential.refreshToken();
        }
        return credential.getAccessToken();
    }

    @Override
    public boolean isMeetingActive(String spaceCode) throws IOException, InterruptedException {
        String token = getValidAccessToken();
        String url = "https://meet.googleapis.com/v2/spaces/" + spaceCode
                + "/participants?filter=latestEndTime+is+null&pageSize=1";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode participants = root.get("participants");
        return participants != null && participants.isArray() && !participants.isEmpty();
    }

    @Override
    public List<String> getActiveParticipantEmails(String spaceCode) throws IOException, InterruptedException {
        String token = getValidAccessToken();
        String url = "https://meet.googleapis.com/v2/spaces/" + spaceCode
                + "/participants?filter=latestEndTime+is+null";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode participants = root.get("participants");

        List<String> emails = new ArrayList<>();
        if (participants != null && participants.isArray()) {
            for (JsonNode participant : participants) {
                JsonNode signedinUser = participant.get("signedinUser");
                if (signedinUser != null) {
                    JsonNode emailNode = signedinUser.get("email");
                    if (emailNode != null && !emailNode.isNull()) {
                        emails.add(emailNode.asText());
                    }
                }
            }
        }
        return emails;
    }
}
