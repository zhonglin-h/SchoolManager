package com.school.service;

import com.school.dto.PersonRequest;
import com.school.dto.PersonResponse;
import com.school.dto.TeacherRequest;
import com.school.dto.TeacherResponse;
import com.school.entity.PersonType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeacherServiceTest {

    @Mock
    PersonService personService;

    @InjectMocks
    TeacherService teacherService;

    @Test
    void getAllActive_returnsOnlyActiveTeachers() {
        when(personService.getAllActive(PersonType.TEACHER))
                .thenReturn(List.of(teacher(1L, "Alice", true)));

        List<TeacherResponse> result = teacherService.getAllActive();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("Alice");
        assertThat(result.get(0).active()).isTrue();
    }

    @Test
    void getAllActive_returnsEmptyListWhenNoneActive() {
        when(personService.getAllActive(PersonType.TEACHER)).thenReturn(List.of());
        assertThat(teacherService.getAllActive()).isEmpty();
    }

    @Test
    void getById_returnsTeacherWhenFound() {
        when(personService.getById(1L)).thenReturn(teacher(1L, "Bob", true));

        TeacherResponse result = teacherService.getById(1L);

        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.name()).isEqualTo("Bob");
    }

    @Test
    void getById_throwsWhenWrongType() {
        when(personService.getById(99L)).thenReturn(student(99L, "Sam", true));

        assertThatThrownBy(() -> teacherService.getById(99L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Teacher not found");
    }

    @Test
    void create_delegatesAndMapsResponse() {
        TeacherRequest req = new TeacherRequest("Carol", "carol@meet.com", null, "555-1234", new BigDecimal("50.00"));
        when(personService.create(org.mockito.ArgumentMatchers.any(PersonRequest.class)))
                .thenReturn(new PersonResponse(
                        5L, PersonType.TEACHER, "Carol", "carol@meet.com",
                        null, null, null, null, null, "555-1234", new BigDecimal("50.00"), true
                ));

        TeacherResponse result = teacherService.create(req);

        assertThat(result.id()).isEqualTo(5L);
        assertThat(result.name()).isEqualTo("Carol");
        assertThat(result.meetEmail()).isEqualTo("carol@meet.com");
        assertThat(result.hourlyRate()).isEqualByComparingTo("50.00");

        ArgumentCaptor<PersonRequest> captor = ArgumentCaptor.forClass(PersonRequest.class);
        verify(personService).create(captor.capture());
        assertThat(captor.getValue().personType()).isEqualTo(PersonType.TEACHER);
        assertThat(captor.getValue().name()).isEqualTo("Carol");
        assertThat(captor.getValue().classroomEmail()).isNull();
        assertThat(captor.getValue().parentEmail()).isNull();
        assertThat(captor.getValue().parentPhone()).isNull();
    }

    @Test
    void update_delegatesAndMapsResponse() {
        when(personService.getById(2L)).thenReturn(teacher(2L, "Dave", true));
        when(personService.update(eq(2L), org.mockito.ArgumentMatchers.any(PersonRequest.class)))
                .thenReturn(new PersonResponse(
                        2L, PersonType.TEACHER, "David", "david@meet.com",
                        null, null, null, null, null, "555-9999", new BigDecimal("60.00"), true
                ));

        TeacherRequest req = new TeacherRequest("David", "david@meet.com", null, "555-9999", new BigDecimal("60.00"));
        TeacherResponse result = teacherService.update(2L, req);

        assertThat(result.name()).isEqualTo("David");
        assertThat(result.meetEmail()).isEqualTo("david@meet.com");
        assertThat(result.hourlyRate()).isEqualByComparingTo("60.00");

        ArgumentCaptor<PersonRequest> captor = ArgumentCaptor.forClass(PersonRequest.class);
        verify(personService).update(eq(2L), captor.capture());
        assertThat(captor.getValue().personType()).isEqualTo(PersonType.TEACHER);
        assertThat(captor.getValue().name()).isEqualTo("David");
    }

    @Test
    void update_throwsWhenWrongType() {
        when(personService.getById(99L)).thenReturn(student(99L, "Sam", true));

        assertThatThrownBy(() -> teacherService.update(99L,
                new TeacherRequest("X", "x@x.com", null, "000", null)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Teacher not found");
    }

    @Test
    void softDelete_delegatesToPersonService() {
        when(personService.getById(3L)).thenReturn(teacher(3L, "Eve", true));

        teacherService.softDelete(3L);

        verify(personService).softDelete(3L);
    }

    @Test
    void softDelete_throwsWhenWrongType() {
        when(personService.getById(99L)).thenReturn(student(99L, "Sam", true));

        assertThatThrownBy(() -> teacherService.softDelete(99L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Teacher not found");
    }

    private PersonResponse teacher(Long id, String name, boolean active) {
        return new PersonResponse(
                id,
                PersonType.TEACHER,
                name,
                name.toLowerCase() + "@meet.com",
                null,
                null,
                null,
                null,
                null,
                "555-0000",
                new BigDecimal("45.00"),
                active
        );
    }

    private PersonResponse student(Long id, String name, boolean active) {
        return new PersonResponse(
                id,
                PersonType.STUDENT,
                name,
                name.toLowerCase() + "@meet.com",
                null,
                null,
                name.toLowerCase() + "@class.com",
                "parent@test.com",
                "555-0000",
                null,
                null,
                active
        );
    }
}
