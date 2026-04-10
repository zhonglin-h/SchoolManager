package com.school.service;

import com.school.dto.TeacherRequest;
import com.school.dto.TeacherResponse;
import com.school.entity.Teacher;
import com.school.repository.TeacherRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TeacherService {

    private final TeacherRepository teacherRepository;

    public TeacherService(TeacherRepository teacherRepository) {
        this.teacherRepository = teacherRepository;
    }

    public List<TeacherResponse> getAllActive() {
        return teacherRepository.findByActiveTrue().stream()
                .map(TeacherResponse::from)
                .toList();
    }

    public TeacherResponse getById(Long id) {
        Teacher teacher = teacherRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Teacher not found: " + id));
        return TeacherResponse.from(teacher);
    }

    public TeacherResponse create(TeacherRequest req) {
        Teacher teacher = Teacher.builder()
                .name(req.name())
                .meetEmail(req.meetEmail())
                .meetDisplayName(req.meetDisplayName())
                .phone(req.phone())
                .hourlyRate(req.hourlyRate())
                .build();
        return TeacherResponse.from(teacherRepository.save(teacher));
    }

    public TeacherResponse update(Long id, TeacherRequest req) {
        Teacher teacher = teacherRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Teacher not found: " + id));
        teacher.setName(req.name());
        teacher.setMeetEmail(req.meetEmail());
        teacher.setMeetDisplayName(req.meetDisplayName());
        teacher.setPhone(req.phone());
        teacher.setHourlyRate(req.hourlyRate());
        return TeacherResponse.from(teacherRepository.save(teacher));
    }

    public void softDelete(Long id) {
        Teacher teacher = teacherRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Teacher not found: " + id));
        teacher.setActive(false);
        teacherRepository.save(teacher);
    }
}
