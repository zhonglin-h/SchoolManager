package com.school.integration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Slf4j
@Component
public class TelegramClient {

    private final RestTemplate restTemplate;
    private final String botToken;
    private final String chatId;

    public TelegramClient(RestTemplate restTemplate,
                          @Value("${telegram.bot-token}") String botToken,
                          @Value("${telegram.chat-id}") String chatId) {
        this.restTemplate = restTemplate;
        this.botToken = botToken;
        this.chatId = chatId;
    }

    public void send(String message) {
        String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
        Map<String, String> body = Map.of("chat_id", chatId, "text", message);
        ResponseEntity<String> response = restTemplate.postForEntity(url, body, String.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("Telegram API returned status: " + response.getStatusCode()
                    + ", body: " + response.getBody());
        }
        log.debug("Telegram message sent successfully");
    }
}
