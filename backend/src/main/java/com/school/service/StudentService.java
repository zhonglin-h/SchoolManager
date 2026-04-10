package com.school.service;

import com.school.dto.StudentRequest;
import com.school.dto.StudentResponse;
import com.school.entity.Student;
import com.school.repository.StudentRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class StudentService {

    private final StudentRepository studentRepository;

    public StudentService(StudentRepository studentRepository) {
        this.studentRepository = studentRepository;
    }

    public List<StudentResponse> getAllActive() {
        return studentRepository.findByActiveTrue().stream()
                .map(StudentResponse::from)
                .toList();
    }

    public StudentResponse getById(Long id) {
        Student student = studentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Student not found: " + id));
        return StudentResponse.from(student);
    }

    public StudentResponse create(StudentRequest req) {
        Student student = Student.builder()
                .name(req.name())
                .meetEmail(req.meetEmail())
                .classroomEmail(req.classroomEmail())
                .parentEmail(req.parentEmail())
                .parentPhone(req.parentPhone())
                .build();
        return StudentResponse.from(studentRepository.save(student));
    }

    public StudentResponse update(Long id, StudentRequest req) {
        Student student = studentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Student not found: " + id));
        student.setName(req.name());
        student.setMeetEmail(req.meetEmail());
        student.setClassroomEmail(req.classroomEmail());
        student.setParentEmail(req.parentEmail());
        student.setParentPhone(req.parentPhone());
        return StudentResponse.from(studentRepository.save(student));
    }

    public void softDelete(Long id) {
        Student student = studentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Student not found: " + id));
        student.setActive(false);
        studentRepository.save(student);
    }
}
