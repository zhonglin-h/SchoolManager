package com.school.service;

import com.school.dto.PersonRequest;
import com.school.dto.PersonResponse;
import com.school.entity.Person;
import com.school.entity.PersonType;
import com.school.repository.PersonRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PersonServiceTest {

    @Mock
    PersonRepository personRepository;

    @InjectMocks
    PersonService personService;

    @Test
    void getAllActive_filtersByTypeWhenProvided() {
        when(personRepository.findByPersonTypeAndActiveTrue(PersonType.STUDENT))
                .thenReturn(List.of(Person.builder().id(1L).personType(PersonType.STUDENT).name("Alice").active(true).build()));

        List<PersonResponse> result = personService.getAllActive(PersonType.STUDENT);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).personType()).isEqualTo(PersonType.STUDENT);
    }

    @Test
    void create_savesPerson() {
        PersonRequest req = new PersonRequest(PersonType.TEACHER, "Carol", "carol@meet.com", null,
                null, null, null, "555-0000", null);
        when(personRepository.save(any(Person.class)))
                .thenAnswer(inv -> {
                    Person p = inv.getArgument(0);
                    p.setId(10L);
                    return p;
                });

        PersonResponse saved = personService.create(req);

        assertThat(saved.id()).isEqualTo(10L);
        assertThat(saved.personType()).isEqualTo(PersonType.TEACHER);
        verify(personRepository).save(any(Person.class));
    }

    @Test
    void softDelete_marksInactive() {
        Person person = Person.builder().id(2L).personType(PersonType.STUDENT).name("Bob").active(true).build();
        when(personRepository.findById(2L)).thenReturn(Optional.of(person));

        personService.softDelete(2L);

        assertThat(person.isActive()).isFalse();
        verify(personRepository).save(person);
    }
}
