package com.school.service;

import com.school.dto.StudentRequest;
import com.school.dto.StudentResponse;
import com.school.dto.PersonRequest;
import com.school.dto.PersonResponse;
import com.school.entity.PersonType;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class StudentService {

    private final PersonService personService;

    public StudentService(PersonService personService) {
        this.personService = personService;
    }

    public List<StudentResponse> getAllActive() {
        return personService.getAllActive(PersonType.STUDENT).stream()
                .map(StudentService::toStudentResponse)
                .toList();
    }

    public StudentResponse getById(Long id) {
        PersonResponse person = personService.getById(id);
        requireStudentType(person, id);
        return toStudentResponse(person);
    }

    public StudentResponse create(StudentRequest req) {
        return toStudentResponse(personService.create(fromStudentRequest(req)));
    }

    public StudentResponse update(Long id, StudentRequest req) {
        PersonResponse existing = personService.getById(id);
        requireStudentType(existing, id);
        return toStudentResponse(personService.update(id, fromStudentRequest(req)));
    }

    public void softDelete(Long id) {
        PersonResponse existing = personService.getById(id);
        requireStudentType(existing, id);
        personService.softDelete(id);
    }

    private static StudentResponse toStudentResponse(PersonResponse p) {
        return new StudentResponse(
                p.id(),
                p.name(),
                p.meetEmail(),
                p.meetDisplayName(),
                p.classroomEmail(),
                p.parentEmail(),
                p.parentPhone(),
                p.active()
        );
    }

    private static PersonRequest fromStudentRequest(StudentRequest req) {
        return new PersonRequest(
                PersonType.STUDENT,
                req.name(),
                req.meetEmail(),
                req.meetDisplayName(),
                req.classroomEmail(),
                req.parentEmail(),
                req.parentPhone(),
                null,
                null
        );
    }

    private static void requireStudentType(PersonResponse person, Long id) {
        if (person.personType() != PersonType.STUDENT) {
            throw new RuntimeException("Student not found: " + id);
        }
    }
}
