package com.school.service;

import com.school.dto.TeacherRequest;
import com.school.dto.TeacherResponse;
import com.school.entity.Person;
import com.school.entity.PersonType;
import com.school.repository.PersonRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TeacherService {

    private final PersonRepository personRepository;

    public TeacherService(PersonRepository personRepository) {
        this.personRepository = personRepository;
    }

    public List<TeacherResponse> getAllActive() {
        return personRepository.findByPersonTypeAndActiveTrue(PersonType.TEACHER).stream()
                .map(TeacherResponse::from)
                .toList();
    }

    public TeacherResponse getById(Long id) {
        Person teacher = personRepository.findById(id)
                .filter(p -> p.getPersonType() == PersonType.TEACHER)
                .orElseThrow(() -> new RuntimeException("Teacher not found: " + id));
        return TeacherResponse.from(teacher);
    }

    public TeacherResponse create(TeacherRequest req) {
        Person teacher = Person.builder()
                .personType(PersonType.TEACHER)
                .name(req.name())
                .meetEmail(req.meetEmail())
                .meetDisplayName(req.meetDisplayName())
                .phone(req.phone())
                .hourlyRate(req.hourlyRate())
                .build();
        return TeacherResponse.from(personRepository.save(teacher));
    }

    public TeacherResponse update(Long id, TeacherRequest req) {
        Person teacher = personRepository.findById(id)
                .filter(p -> p.getPersonType() == PersonType.TEACHER)
                .orElseThrow(() -> new RuntimeException("Teacher not found: " + id));
        teacher.setName(req.name());
        teacher.setMeetEmail(req.meetEmail());
        teacher.setMeetDisplayName(req.meetDisplayName());
        teacher.setPhone(req.phone());
        teacher.setHourlyRate(req.hourlyRate());
        return TeacherResponse.from(personRepository.save(teacher));
    }

    public void softDelete(Long id) {
        Person teacher = personRepository.findById(id)
                .filter(p -> p.getPersonType() == PersonType.TEACHER)
                .orElseThrow(() -> new RuntimeException("Teacher not found: " + id));
        teacher.setActive(false);
        personRepository.save(teacher);
    }
}
