package com.school.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.school.integration.JoinAutomationClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.mail.javamail.JavaMailSender;

@SpringBootTest
@TestPropertySource("classpath:application-local.properties")
@Tag("manual")
class TelegramConfigIntegrationTest {

    @Autowired
    Environment env;

    @Autowired
    TelegramClient telegramClient;

    @Autowired
    RestTemplate restTemplate;

    @MockBean
    JavaMailSender javaMailSender;

    @MockBean
    JoinAutomationClient joinAutomationClient;

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        RestTemplate telegramRestTemplate() {
            return new RestTemplate();
        }
    }

    @Test
    void telegramPropertiesLoaded_intoEnvironmentAndBean() {
        String bot = env.getProperty("telegram.bot-token");
        String chat = env.getProperty("telegram.chat-id");

        assertThat(bot).as("telegram.bot-token should be set in application-local.properties").isNotBlank();
        assertThat(chat).as("telegram.chat-id should be set in application-local.properties").isNotBlank();

        String botFromBean = (String) ReflectionTestUtils.getField(telegramClient, "botToken");
        String chatFromBean = (String) ReflectionTestUtils.getField(telegramClient, "chatId");

        assertThat(botFromBean).isEqualTo(bot);
        assertThat(chatFromBean).isEqualTo(chat);
    }

    @Test
    void sendsMessage_toTelegramApi_whenCredentialsPresent() {
        String bot = env.getProperty("telegram.bot-token");
        String chat = env.getProperty("telegram.chat-id");

        // Ensure properties are present
        assertThat(bot).isNotBlank();
        assertThat(chat).isNotBlank();

        String url = "https://api.telegram.org/bot" + bot + "/sendMessage";
        var body = java.util.Map.of("chat_id", chat, "text", "Integration test message — please ignore");

        var response = restTemplate.postForEntity(url, body, java.util.Map.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        var respBody = response.getBody();
        assertThat(respBody).isNotNull();
        assertThat(respBody.get("ok")).isEqualTo(Boolean.TRUE);
    }
}
