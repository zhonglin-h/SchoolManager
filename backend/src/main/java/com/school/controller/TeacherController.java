package com.school.controller;

import com.school.dto.TeacherRequest;
import com.school.dto.TeacherResponse;
import com.school.service.TeacherService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/teachers")
public class TeacherController {

    private final TeacherService teacherService;

    public TeacherController(TeacherService teacherService) {
        this.teacherService = teacherService;
    }

    @GetMapping
    public ResponseEntity<List<TeacherResponse>> getAllActive() {
        return ResponseEntity.ok(teacherService.getAllActive());
    }

    @GetMapping("/{id}")
    public ResponseEntity<TeacherResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(teacherService.getById(id));
    }

    @PostMapping
    public ResponseEntity<TeacherResponse> create(@RequestBody TeacherRequest req) {
        return ResponseEntity.ok(teacherService.create(req));
    }

    @PutMapping("/{id}")
    public ResponseEntity<TeacherResponse> update(@PathVariable Long id, @RequestBody TeacherRequest req) {
        return ResponseEntity.ok(teacherService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        teacherService.softDelete(id);
        return ResponseEntity.noContent().build();
    }
}
