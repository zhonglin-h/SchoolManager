package com.school.service;

import com.school.dto.PersonRequest;
import com.school.dto.PersonResponse;
import com.school.entity.Person;
import com.school.entity.PersonType;
import com.school.repository.PersonRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PersonService {

    private final PersonRepository personRepository;

    public PersonService(PersonRepository personRepository) {
        this.personRepository = personRepository;
    }

    public List<PersonResponse> getAllActive(PersonType personType) {
        return getAll(personType, false);
    }

    public List<PersonResponse> getAll(PersonType personType, boolean includeInactive) {
        if (!includeInactive) {
            List<Person> people = personType == null
                    ? personRepository.findByActiveTrue()
                    : personRepository.findByPersonTypeAndActiveTrue(personType);
            return people.stream().map(PersonResponse::from).toList();
        }

        List<Person> people = personType == null
                ? personRepository.findAll()
                : personRepository.findByPersonType(personType);
        return people.stream().map(PersonResponse::from).toList();
    }

    public PersonResponse getById(Long id) {
        return PersonResponse.from(personRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Person not found: " + id)));
    }

    public PersonResponse create(PersonRequest req) {
        validateUniqueMeetEmailForCreate(req.meetEmail());
        Person person = Person.builder()
                .personType(req.personType())
                .name(req.name())
                .meetEmail(req.meetEmail())
                .meetDisplayName(req.meetDisplayName())
                .classroomEmail(req.classroomEmail())
                .parentEmail(req.parentEmail())
                .parentPhone(req.parentPhone())
                .phone(req.phone())
                .hourlyRate(req.hourlyRate())
                .build();
        return PersonResponse.from(personRepository.save(person));
    }

    public PersonResponse update(Long id, PersonRequest req) {
        Person person = personRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Person not found: " + id));
        if (req.personType() != null && req.personType() != person.getPersonType()) {
            throw new RuntimeException("Person type cannot be changed for id: " + id);
        }
        validateUniqueMeetEmailForUpdate(req.meetEmail(), id);
        person.setName(req.name());
        person.setMeetEmail(req.meetEmail());
        person.setMeetDisplayName(req.meetDisplayName());
        person.setClassroomEmail(req.classroomEmail());
        person.setParentEmail(req.parentEmail());
        person.setParentPhone(req.parentPhone());
        person.setPhone(req.phone());
        person.setHourlyRate(req.hourlyRate());
        return PersonResponse.from(personRepository.save(person));
    }

    public void softDelete(Long id) {
        Person person = personRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Person not found: " + id));
        person.setActive(false);
        personRepository.save(person);
    }

    private void validateUniqueMeetEmailForCreate(String meetEmail) {
        if (meetEmail == null || meetEmail.isBlank()) return;
        if (personRepository.existsByMeetEmail(meetEmail)) {
            throw new RuntimeException("Duplicate meetEmail not allowed: " + meetEmail);
        }
    }

    private void validateUniqueMeetEmailForUpdate(String meetEmail, Long id) {
        if (meetEmail == null || meetEmail.isBlank()) return;
        if (personRepository.existsByMeetEmailAndIdNot(meetEmail, id)) {
            throw new RuntimeException("Duplicate meetEmail not allowed: " + meetEmail);
        }
    }
}
