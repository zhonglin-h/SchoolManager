package com.school.repository;

import com.school.entity.Person;
import com.school.entity.PersonType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PersonRepository extends JpaRepository<Person, Long> {
    List<Person> findByPersonTypeAndActiveTrue(PersonType personType);
    List<Person> findByActiveTrue();

    Optional<Person> findByPersonTypeAndMeetEmail(PersonType personType, String meetEmail);
    Optional<Person> findByPersonTypeAndMeetEmailAndActiveTrue(PersonType personType, String meetEmail);

    Optional<Person> findByPersonTypeAndGoogleUserId(PersonType personType, String googleUserId);
    Optional<Person> findByPersonTypeAndGoogleUserIdAndActiveTrue(PersonType personType, String googleUserId);

    Optional<Person> findByPersonTypeAndMeetDisplayNameIgnoreCase(PersonType personType, String meetDisplayName);
    Optional<Person> findByPersonTypeAndMeetDisplayNameIgnoreCaseAndActiveTrue(PersonType personType, String meetDisplayName);

    Optional<Person> findByPersonTypeAndNameIgnoreCase(PersonType personType, String name);
    Optional<Person> findByPersonTypeAndNameIgnoreCaseAndActiveTrue(PersonType personType, String name);
}
