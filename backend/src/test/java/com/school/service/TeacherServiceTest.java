package com.school.service;

import com.school.dto.TeacherRequest;
import com.school.dto.TeacherResponse;
import com.school.entity.Person;
import com.school.entity.PersonType;
import com.school.repository.PersonRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeacherServiceTest {

    @Mock
    PersonRepository personRepository;

    @InjectMocks
    TeacherService teacherService;

    // --- getAllActive ---

    @Test
    void getAllActive_returnsOnlyActiveTeachers() {
        Person active = teacher(1L, "Alice", true);
        when(personRepository.findByPersonTypeAndActiveTrue(PersonType.TEACHER)).thenReturn(List.of(active));

        List<TeacherResponse> result = teacherService.getAllActive();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("Alice");
        assertThat(result.get(0).active()).isTrue();
    }

    @Test
    void getAllActive_returnsEmptyListWhenNoneActive() {
        when(personRepository.findByPersonTypeAndActiveTrue(PersonType.TEACHER)).thenReturn(List.of());

        assertThat(teacherService.getAllActive()).isEmpty();
    }

    // --- getById ---

    @Test
    void getById_returnsTeacherWhenFound() {
        when(personRepository.findById(1L)).thenReturn(Optional.of(teacher(1L, "Bob", true)));

        TeacherResponse result = teacherService.getById(1L);

        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.name()).isEqualTo("Bob");
    }

    @Test
    void getById_throwsWhenNotFound() {
        when(personRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> teacherService.getById(99L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("99");
    }

    // --- create ---

    @Test
    void create_savesAndReturnsNewTeacher() {
        TeacherRequest req = new TeacherRequest("Carol", "carol@meet.com", null, "555-1234", new BigDecimal("50.00"));
        Person saved = Person.builder()
                .id(5L).name("Carol").meetEmail("carol@meet.com")
                .personType(PersonType.TEACHER)
                .phone("555-1234").hourlyRate(new BigDecimal("50.00")).build();
        when(personRepository.save(any(Person.class))).thenReturn(saved);

        TeacherResponse result = teacherService.create(req);

        assertThat(result.id()).isEqualTo(5L);
        assertThat(result.name()).isEqualTo("Carol");
        assertThat(result.meetEmail()).isEqualTo("carol@meet.com");

        ArgumentCaptor<Person> captor = ArgumentCaptor.forClass(Person.class);
        verify(personRepository).save(captor.capture());
        assertThat(captor.getValue().getPersonType()).isEqualTo(PersonType.TEACHER);
        assertThat(captor.getValue().isActive()).isTrue();
    }

    // --- update ---

    @Test
    void update_updatesFieldsAndSaves() {
        Person existing = teacher(2L, "Dave", true);
        when(personRepository.findById(2L)).thenReturn(Optional.of(existing));
        when(personRepository.save(any(Person.class))).thenAnswer(inv -> inv.getArgument(0));

        TeacherRequest req = new TeacherRequest("David", "david@meet.com", null, "555-9999", new BigDecimal("60.00"));
        TeacherResponse result = teacherService.update(2L, req);

        assertThat(result.name()).isEqualTo("David");
        assertThat(result.meetEmail()).isEqualTo("david@meet.com");
        assertThat(result.hourlyRate()).isEqualByComparingTo("60.00");
    }

    @Test
    void update_throwsWhenNotFound() {
        when(personRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> teacherService.update(99L,
                new TeacherRequest("X", "x@x.com", null, "000", null)))
                .isInstanceOf(RuntimeException.class);
    }

    // --- softDelete ---

    @Test
    void softDelete_setsActiveFalse() {
        Person existing = teacher(3L, "Eve", true);
        when(personRepository.findById(3L)).thenReturn(Optional.of(existing));
        when(personRepository.save(any(Person.class))).thenAnswer(inv -> inv.getArgument(0));

        teacherService.softDelete(3L);

        ArgumentCaptor<Person> captor = ArgumentCaptor.forClass(Person.class);
        verify(personRepository).save(captor.capture());
        assertThat(captor.getValue().isActive()).isFalse();
    }

    @Test
    void softDelete_throwsWhenNotFound() {
        when(personRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> teacherService.softDelete(99L))
                .isInstanceOf(RuntimeException.class);
    }

    // --- helpers ---

    private Person teacher(Long id, String name, boolean active) {
        return Person.builder().id(id).name(name)
                .personType(PersonType.TEACHER)
                .meetEmail(name.toLowerCase() + "@meet.com")
                .phone("555-0000")
                .hourlyRate(new BigDecimal("45.00"))
                .active(active).build();
    }
}
