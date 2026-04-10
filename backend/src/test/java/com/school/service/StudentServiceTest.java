package com.school.service;

import com.school.dto.StudentRequest;
import com.school.dto.StudentResponse;
import com.school.entity.Student;
import com.school.repository.StudentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StudentServiceTest {

    @Mock
    StudentRepository studentRepository;

    @InjectMocks
    StudentService studentService;

    // --- getAllActive ---

    @Test
    void getAllActive_returnsOnlyActiveStudents() {
        Student active = student(1L, "Alice", true);
        when(studentRepository.findByActiveTrue()).thenReturn(List.of(active));

        List<StudentResponse> result = studentService.getAllActive();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("Alice");
        assertThat(result.get(0).active()).isTrue();
    }

    @Test
    void getAllActive_returnsEmptyListWhenNoneActive() {
        when(studentRepository.findByActiveTrue()).thenReturn(List.of());

        assertThat(studentService.getAllActive()).isEmpty();
    }

    // --- getById ---

    @Test
    void getById_returnsStudentWhenFound() {
        when(studentRepository.findById(1L)).thenReturn(Optional.of(student(1L, "Bob", true)));

        StudentResponse result = studentService.getById(1L);

        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.name()).isEqualTo("Bob");
    }

    @Test
    void getById_throwsWhenNotFound() {
        when(studentRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> studentService.getById(99L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("99");
    }

    // --- create ---

    @Test
    void create_savesAndReturnsNewStudent() {
        StudentRequest req = new StudentRequest("Carol", "carol@meet.com", null, "carol@classroom.com",
                "parent@test.com", "555-1234");
        Student saved = Student.builder()
                .id(5L).name("Carol").meetEmail("carol@meet.com")
                .classroomEmail("carol@classroom.com")
                .parentEmail("parent@test.com").parentPhone("555-1234").build();
        when(studentRepository.save(any(Student.class))).thenReturn(saved);

        StudentResponse result = studentService.create(req);

        assertThat(result.id()).isEqualTo(5L);
        assertThat(result.name()).isEqualTo("Carol");
        assertThat(result.meetEmail()).isEqualTo("carol@meet.com");

        ArgumentCaptor<Student> captor = ArgumentCaptor.forClass(Student.class);
        verify(studentRepository).save(captor.capture());
        assertThat(captor.getValue().isActive()).isTrue();
    }

    // --- update ---

    @Test
    void update_updatesFieldsAndSaves() {
        Student existing = student(2L, "Dave", true);
        when(studentRepository.findById(2L)).thenReturn(Optional.of(existing));
        when(studentRepository.save(any(Student.class))).thenAnswer(inv -> inv.getArgument(0));

        StudentRequest req = new StudentRequest("David", "david@meet.com", null, "david@class.com",
                "dp@test.com", "555-9999");
        StudentResponse result = studentService.update(2L, req);

        assertThat(result.name()).isEqualTo("David");
        assertThat(result.meetEmail()).isEqualTo("david@meet.com");
    }

    @Test
    void update_throwsWhenNotFound() {
        when(studentRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> studentService.update(99L,
                new StudentRequest("X", "x@x.com", null, "x@x.com", "x@x.com", "000")))
                .isInstanceOf(RuntimeException.class);
    }

    // --- softDelete ---

    @Test
    void softDelete_setsActiveFalse() {
        Student existing = student(3L, "Eve", true);
        when(studentRepository.findById(3L)).thenReturn(Optional.of(existing));
        when(studentRepository.save(any(Student.class))).thenAnswer(inv -> inv.getArgument(0));

        studentService.softDelete(3L);

        ArgumentCaptor<Student> captor = ArgumentCaptor.forClass(Student.class);
        verify(studentRepository).save(captor.capture());
        assertThat(captor.getValue().isActive()).isFalse();
    }

    @Test
    void softDelete_throwsWhenNotFound() {
        when(studentRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> studentService.softDelete(99L))
                .isInstanceOf(RuntimeException.class);
    }

    // --- helpers ---

    private Student student(Long id, String name, boolean active) {
        return Student.builder().id(id).name(name)
                .meetEmail(name.toLowerCase() + "@meet.com")
                .classroomEmail(name.toLowerCase() + "@class.com")
                .parentEmail("parent@test.com").parentPhone("555-0000")
                .active(active).build();
    }
}
