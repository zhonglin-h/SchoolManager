package com.school.controller;

import com.school.service.NotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/settings")
public class SettingsController {

    private final NotificationService notificationService;

    public SettingsController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> get() {
        return ResponseEntity.ok(Map.of(
                "notificationsEnabled", notificationService.isNotificationsEnabled(),
                "emailNotificationsEnabled", notificationService.isEmailNotificationsEnabled()
        ));
    }
}
