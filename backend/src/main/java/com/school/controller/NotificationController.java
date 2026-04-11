package com.school.controller;

import com.school.dto.NotificationLogResponse;
import com.school.integration.EmailClient;
import com.school.repository.NotificationLogRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationLogRepository notificationLogRepository;
    private final EmailClient emailClient;

    @Value("${app.principal.email}")
    private String principalEmail;

    public NotificationController(NotificationLogRepository notificationLogRepository, EmailClient emailClient) {
        this.notificationLogRepository = notificationLogRepository;
        this.emailClient = emailClient;
    }

    @GetMapping
    public ResponseEntity<List<NotificationLogResponse>> getAll() {
        List<NotificationLogResponse> responses = notificationLogRepository.findAllByOrderBySentAtDesc()
                .stream()
                .map(NotificationLogResponse::from)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @PostMapping("/test")
    public ResponseEntity<String> sendTestEmail() {
        emailClient.send(principalEmail, "Test Notification", "This is a test email from School Manager.");
        return ResponseEntity.ok("Test email sent to " + principalEmail);
    }
}
