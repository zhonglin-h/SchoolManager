package com.school.service;

import com.school.dto.StudentRequest;
import com.school.dto.StudentResponse;
import com.school.entity.Person;
import com.school.entity.PersonType;
import com.school.repository.PersonRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class StudentService {

    private final PersonRepository personRepository;

    public StudentService(PersonRepository personRepository) {
        this.personRepository = personRepository;
    }

    public List<StudentResponse> getAllActive() {
        return personRepository.findByPersonTypeAndActiveTrue(PersonType.STUDENT).stream()
                .map(StudentResponse::from)
                .toList();
    }

    public StudentResponse getById(Long id) {
        Person student = personRepository.findById(id)
                .filter(p -> p.getPersonType() == PersonType.STUDENT)
                .orElseThrow(() -> new RuntimeException("Student not found: " + id));
        return StudentResponse.from(student);
    }

    public StudentResponse create(StudentRequest req) {
        Person student = Person.builder()
                .personType(PersonType.STUDENT)
                .name(req.name())
                .meetEmail(req.meetEmail())
                .meetDisplayName(req.meetDisplayName())
                .classroomEmail(req.classroomEmail())
                .parentEmail(req.parentEmail())
                .parentPhone(req.parentPhone())
                .build();
        return StudentResponse.from(personRepository.save(student));
    }

    public StudentResponse update(Long id, StudentRequest req) {
        Person student = personRepository.findById(id)
                .filter(p -> p.getPersonType() == PersonType.STUDENT)
                .orElseThrow(() -> new RuntimeException("Student not found: " + id));
        student.setName(req.name());
        student.setMeetEmail(req.meetEmail());
        student.setMeetDisplayName(req.meetDisplayName());
        student.setClassroomEmail(req.classroomEmail());
        student.setParentEmail(req.parentEmail());
        student.setParentPhone(req.parentPhone());
        return StudentResponse.from(personRepository.save(student));
    }

    public void softDelete(Long id) {
        Person student = personRepository.findById(id)
                .filter(p -> p.getPersonType() == PersonType.STUDENT)
                .orElseThrow(() -> new RuntimeException("Student not found: " + id));
        student.setActive(false);
        personRepository.save(student);
    }
}
