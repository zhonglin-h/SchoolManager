package com.school.service;

import com.school.dto.TeacherRequest;
import com.school.dto.TeacherResponse;
import com.school.dto.PersonRequest;
import com.school.dto.PersonResponse;
import com.school.entity.PersonType;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TeacherService {

    private final PersonService personService;

    public TeacherService(PersonService personService) {
        this.personService = personService;
    }

    public List<TeacherResponse> getAll(boolean includeInactive) {
        return personService.getAll(PersonType.TEACHER, includeInactive).stream()
                .map(TeacherService::toTeacherResponse)
                .toList();
    }

    public TeacherResponse getById(Long id) {
        PersonResponse person = personService.getById(id);
        requireTeacherType(person, id);
        return toTeacherResponse(person);
    }

    public TeacherResponse create(TeacherRequest req) {
        return toTeacherResponse(personService.create(fromTeacherRequest(req)));
    }

    public TeacherResponse update(Long id, TeacherRequest req) {
        PersonResponse existing = personService.getById(id);
        requireTeacherType(existing, id);
        return toTeacherResponse(personService.update(id, fromTeacherRequest(req)));
    }

    public void softDelete(Long id) {
        PersonResponse existing = personService.getById(id);
        requireTeacherType(existing, id);
        personService.softDelete(id);
    }

    public TeacherResponse activate(Long id) {
        PersonResponse existing = personService.getById(id);
        requireTeacherType(existing, id);
        return toTeacherResponse(personService.activate(id));
    }

    private static TeacherResponse toTeacherResponse(PersonResponse p) {
        return new TeacherResponse(
                p.id(),
                p.name(),
                p.meetEmail(),
                p.meetDisplayName(),
                p.phone(),
                p.hourlyRate(),
                p.active()
        );
    }

    private static PersonRequest fromTeacherRequest(TeacherRequest req) {
        return new PersonRequest(
                PersonType.TEACHER,
                req.name(),
                req.meetEmail(),
                req.meetDisplayName(),
                null,
                null,
                null,
                req.phone(),
                req.hourlyRate()
        );
    }

    private static void requireTeacherType(PersonResponse person, Long id) {
        if (person.personType() != PersonType.TEACHER) {
            throw new RuntimeException("Teacher not found: " + id);
        }
    }
}
