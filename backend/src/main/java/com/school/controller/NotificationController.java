package com.school.controller;

import com.school.dto.NotificationLogResponse;
import com.school.repository.NotificationLogRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationLogRepository notificationLogRepository;

    public NotificationController(NotificationLogRepository notificationLogRepository) {
        this.notificationLogRepository = notificationLogRepository;
    }

    @GetMapping
    public ResponseEntity<List<NotificationLogResponse>> getAll() {
        List<NotificationLogResponse> responses = notificationLogRepository.findAllByOrderBySentAtDesc()
                .stream()
                .map(NotificationLogResponse::from)
                .toList();
        return ResponseEntity.ok(responses);
    }
}
