package com.school.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.auth.oauth2.Credential;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.meet.mock", havingValue = "false", matchIfMissing = true)
public class GoogleMeetClient implements MeetClient {

    private static final String MEET_API = "https://meet.googleapis.com/v2";

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
        String token = credential.getAccessToken();
        if (token == null) {
            throw new IOException("Failed to obtain Google access token");
        }
        return token;
    }

    private JsonNode getSpace(String spaceCode) throws IOException, InterruptedException {
        String token = getValidAccessToken();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(MEET_API + "/spaces/" + spaceCode))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        log.debug("GET /spaces/{} -> HTTP {}", spaceCode, response.statusCode());
        if (response.statusCode() != 200) return null;
        return objectMapper.readTree(response.body());
    }

    @Override
    public boolean isMeetingActive(String spaceCode) throws IOException, InterruptedException {
        JsonNode space = getSpace(spaceCode);
        if (space == null) return false;
        JsonNode activeConference = space.get("activeConference");
        return activeConference != null && !activeConference.isNull();
    }

    @Override
    public List<MeetParticipant> getActiveParticipants(String spaceCode) throws IOException, InterruptedException {
        JsonNode space = getSpace(spaceCode);
        if (space == null) return List.of();

        JsonNode activeConference = space.get("activeConference");
        if (activeConference == null || activeConference.isNull()) return List.of();

        String conferenceRecord = activeConference.path("conferenceRecord").asText(null);
        if (conferenceRecord == null) return List.of();

        String token = getValidAccessToken();
        // No server-side filter — latestEndTime absent means still in the meeting; filter client-side
        String url = MEET_API + "/" + conferenceRecord + "/participants";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        log.info("GET participants ({}) -> HTTP {} body: {}", spaceCode, response.statusCode(), response.body());

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode participants = root.get("participants");

        List<MeetParticipant> result = new ArrayList<>();
        if (participants != null && participants.isArray()) {
            for (JsonNode participant : participants) {
                // Skip participants who have already left (latestEndTime present)
                if (participant.has("latestEndTime")) continue;
                log.info("  participant node keys: {}", participant.fieldNames());
                JsonNode signedinUser = participant.get("signedinUser");
                if (signedinUser != null) {
                    String displayName = signedinUser.path("displayName").asText(null);
                    // "users/118377222510251534014" → "118377222510251534014"
                    String userResource = signedinUser.path("user").asText(null);
                    String googleUserId = userResource != null && userResource.startsWith("users/")
                            ? userResource.substring("users/".length())
                            : userResource;
                    log.info("  signedinUser -> googleUserId={} displayName={}", googleUserId, displayName);
                    result.add(new MeetParticipant(googleUserId, displayName));
                } else {
                    JsonNode anonymousUser = participant.get("anonymousUser");
                    if (anonymousUser != null) {
                        String displayName = anonymousUser.path("displayName").asText(null);
                        log.info("  anonymousUser -> displayName={}", displayName);
                        result.add(new MeetParticipant(null, displayName));
                    } else {
                        log.info("  unknown participant structure: {}", participant);
                    }
                }
            }
        }
        return result;
    }
}
