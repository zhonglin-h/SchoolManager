package com.school.service;

import com.school.dto.TeacherRequest;
import com.school.dto.TeacherResponse;
import com.school.entity.Teacher;
import com.school.repository.TeacherRepository;
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
    TeacherRepository teacherRepository;

    @InjectMocks
    TeacherService teacherService;

    // --- getAllActive ---

    @Test
    void getAllActive_returnsOnlyActiveTeachers() {
        Teacher active = teacher(1L, "Alice", true);
        when(teacherRepository.findByActiveTrue()).thenReturn(List.of(active));

        List<TeacherResponse> result = teacherService.getAllActive();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("Alice");
        assertThat(result.get(0).active()).isTrue();
    }

    @Test
    void getAllActive_returnsEmptyListWhenNoneActive() {
        when(teacherRepository.findByActiveTrue()).thenReturn(List.of());

        assertThat(teacherService.getAllActive()).isEmpty();
    }

    // --- getById ---

    @Test
    void getById_returnsTeacherWhenFound() {
        when(teacherRepository.findById(1L)).thenReturn(Optional.of(teacher(1L, "Bob", true)));

        TeacherResponse result = teacherService.getById(1L);

        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.name()).isEqualTo("Bob");
    }

    @Test
    void getById_throwsWhenNotFound() {
        when(teacherRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> teacherService.getById(99L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("99");
    }

    // --- create ---

    @Test
    void create_savesAndReturnsNewTeacher() {
        TeacherRequest req = new TeacherRequest("Carol", "carol@meet.com", "555-1234", new BigDecimal("50.00"));
        Teacher saved = Teacher.builder()
                .id(5L).name("Carol").meetEmail("carol@meet.com")
                .phone("555-1234").hourlyRate(new BigDecimal("50.00")).build();
        when(teacherRepository.save(any(Teacher.class))).thenReturn(saved);

        TeacherResponse result = teacherService.create(req);

        assertThat(result.id()).isEqualTo(5L);
        assertThat(result.name()).isEqualTo("Carol");
        assertThat(result.meetEmail()).isEqualTo("carol@meet.com");

        ArgumentCaptor<Teacher> captor = ArgumentCaptor.forClass(Teacher.class);
        verify(teacherRepository).save(captor.capture());
        assertThat(captor.getValue().isActive()).isTrue();
    }

    // --- update ---

    @Test
    void update_updatesFieldsAndSaves() {
        Teacher existing = teacher(2L, "Dave", true);
        when(teacherRepository.findById(2L)).thenReturn(Optional.of(existing));
        when(teacherRepository.save(any(Teacher.class))).thenAnswer(inv -> inv.getArgument(0));

        TeacherRequest req = new TeacherRequest("David", "david@meet.com", "555-9999", new BigDecimal("60.00"));
        TeacherResponse result = teacherService.update(2L, req);

        assertThat(result.name()).isEqualTo("David");
        assertThat(result.meetEmail()).isEqualTo("david@meet.com");
        assertThat(result.hourlyRate()).isEqualByComparingTo("60.00");
    }

    @Test
    void update_throwsWhenNotFound() {
        when(teacherRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> teacherService.update(99L,
                new TeacherRequest("X", "x@x.com", "000", null)))
                .isInstanceOf(RuntimeException.class);
    }

    // --- softDelete ---

    @Test
    void softDelete_setsActiveFalse() {
        Teacher existing = teacher(3L, "Eve", true);
        when(teacherRepository.findById(3L)).thenReturn(Optional.of(existing));
        when(teacherRepository.save(any(Teacher.class))).thenAnswer(inv -> inv.getArgument(0));

        teacherService.softDelete(3L);

        ArgumentCaptor<Teacher> captor = ArgumentCaptor.forClass(Teacher.class);
        verify(teacherRepository).save(captor.capture());
        assertThat(captor.getValue().isActive()).isFalse();
    }

    @Test
    void softDelete_throwsWhenNotFound() {
        when(teacherRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> teacherService.softDelete(99L))
                .isInstanceOf(RuntimeException.class);
    }

    // --- helpers ---

    private Teacher teacher(Long id, String name, boolean active) {
        return Teacher.builder().id(id).name(name)
                .meetEmail(name.toLowerCase() + "@meet.com")
                .phone("555-0000")
                .hourlyRate(new BigDecimal("45.00"))
                .active(active).build();
    }
}
