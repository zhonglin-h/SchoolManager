package com.school.service;

import com.school.dto.PersonRequest;
import com.school.dto.PersonResponse;
import com.school.dto.StudentRequest;
import com.school.dto.StudentResponse;
import com.school.entity.PersonType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StudentServiceTest {

    @Mock
    PersonService personService;

    @InjectMocks
    StudentService studentService;

    @Test
    void getAllActive_returnsOnlyActiveStudents() {
        when(personService.getAllActive(PersonType.STUDENT))
                .thenReturn(List.of(student(1L, "Alice", true)));

        List<StudentResponse> result = studentService.getAllActive();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("Alice");
        assertThat(result.get(0).active()).isTrue();
    }

    @Test
    void getAllActive_returnsEmptyListWhenNoneActive() {
        when(personService.getAllActive(PersonType.STUDENT)).thenReturn(List.of());
        assertThat(studentService.getAllActive()).isEmpty();
    }

    @Test
    void getById_returnsStudentWhenFound() {
        when(personService.getById(1L)).thenReturn(student(1L, "Bob", true));

        StudentResponse result = studentService.getById(1L);

        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.name()).isEqualTo("Bob");
    }

    @Test
    void getById_throwsWhenWrongType() {
        when(personService.getById(99L)).thenReturn(teacher(99L, "Tina", true));

        assertThatThrownBy(() -> studentService.getById(99L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Student not found");
    }

    @Test
    void create_delegatesAndMapsResponse() {
        StudentRequest req = new StudentRequest("Carol", "carol@meet.com", null, "carol@classroom.com",
                "parent@test.com", "555-1234");
        when(personService.create(org.mockito.ArgumentMatchers.any(PersonRequest.class)))
                .thenReturn(student(5L, "Carol", true));

        StudentResponse result = studentService.create(req);

        assertThat(result.id()).isEqualTo(5L);
        assertThat(result.name()).isEqualTo("Carol");
        assertThat(result.meetEmail()).isEqualTo("carol@meet.com");

        ArgumentCaptor<PersonRequest> captor = ArgumentCaptor.forClass(PersonRequest.class);
        verify(personService).create(captor.capture());
        assertThat(captor.getValue().personType()).isEqualTo(PersonType.STUDENT);
        assertThat(captor.getValue().name()).isEqualTo("Carol");
        assertThat(captor.getValue().phone()).isNull();
        assertThat(captor.getValue().hourlyRate()).isNull();
    }

    @Test
    void update_delegatesAndMapsResponse() {
        when(personService.getById(2L)).thenReturn(student(2L, "Dave", true));
        when(personService.update(eq(2L), org.mockito.ArgumentMatchers.any(PersonRequest.class)))
                .thenReturn(student(2L, "David", true));

        StudentRequest req = new StudentRequest("David", "david@meet.com", null, "david@class.com",
                "dp@test.com", "555-9999");
        StudentResponse result = studentService.update(2L, req);

        assertThat(result.name()).isEqualTo("David");
        assertThat(result.meetEmail()).isEqualTo("david@meet.com");

        ArgumentCaptor<PersonRequest> captor = ArgumentCaptor.forClass(PersonRequest.class);
        verify(personService).update(eq(2L), captor.capture());
        assertThat(captor.getValue().personType()).isEqualTo(PersonType.STUDENT);
        assertThat(captor.getValue().name()).isEqualTo("David");
    }

    @Test
    void update_throwsWhenWrongType() {
        when(personService.getById(99L)).thenReturn(teacher(99L, "Tina", true));

        assertThatThrownBy(() -> studentService.update(99L,
                new StudentRequest("X", "x@x.com", null, "x@x.com", "x@x.com", "000")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Student not found");
    }

    @Test
    void softDelete_delegatesToPersonService() {
        when(personService.getById(3L)).thenReturn(student(3L, "Eve", true));

        studentService.softDelete(3L);

        verify(personService).softDelete(3L);
    }

    @Test
    void softDelete_throwsWhenWrongType() {
        when(personService.getById(99L)).thenReturn(teacher(99L, "Tina", true));

        assertThatThrownBy(() -> studentService.softDelete(99L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Student not found");
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
                java.math.BigDecimal.ONE,
                active
        );
    }
}
