package com.school.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.school.dto.TeacherRequest;
import com.school.dto.TeacherResponse;
import com.school.service.TeacherService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TeacherController.class)
class TeacherControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean TeacherService teacherService;

    // --- GET /teachers ---

    @Test
    void getAll_returnsListOfActiveTeachers() throws Exception {
        when(teacherService.getAllActive()).thenReturn(List.of(
                response(1L, "Alice"),
                response(2L, "Bob")
        ));

        mockMvc.perform(get("/teachers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Alice"))
                .andExpect(jsonPath("$[1].name").value("Bob"));
    }

    @Test
    void getAll_returnsEmptyList() throws Exception {
        when(teacherService.getAllActive()).thenReturn(List.of());

        mockMvc.perform(get("/teachers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // --- GET /teachers/{id} ---

    @Test
    void getById_returnsTeacher() throws Exception {
        when(teacherService.getById(1L)).thenReturn(response(1L, "Alice"));

        mockMvc.perform(get("/teachers/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Alice"));
    }

    @Test
    void getById_throwsWhenNotFound() {
        when(teacherService.getById(99L)).thenThrow(new RuntimeException("Teacher not found: 99"));

        assertThatThrownBy(() -> mockMvc.perform(get("/teachers/99")))
                .hasRootCauseInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("Teacher not found: 99");
    }

    // --- POST /teachers ---

    @Test
    void create_returnsCreatedTeacher() throws Exception {
        TeacherRequest req = new TeacherRequest("Carol", "carol@meet.com", "555-2222", new BigDecimal("50.00"));
        when(teacherService.create(any(TeacherRequest.class))).thenReturn(response(3L, "Carol"));

        mockMvc.perform(post("/teachers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(3))
                .andExpect(jsonPath("$.name").value("Carol"));

        verify(teacherService).create(any(TeacherRequest.class));
    }

    // --- PUT /teachers/{id} ---

    @Test
    void update_returnsUpdatedTeacher() throws Exception {
        TeacherRequest req = new TeacherRequest("Alice Updated", "alice@meet.com", "555-1111", new BigDecimal("55.00"));
        when(teacherService.update(eq(1L), any(TeacherRequest.class)))
                .thenReturn(response(1L, "Alice Updated"));

        mockMvc.perform(put("/teachers/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Alice Updated"));
    }

    @Test
    void update_throwsWhenNotFound() {
        when(teacherService.update(eq(99L), any(TeacherRequest.class)))
                .thenThrow(new RuntimeException("Teacher not found: 99"));

        assertThatThrownBy(() -> mockMvc.perform(put("/teachers/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TeacherRequest("X", "x@x.com", "000", null)))))
                .hasRootCauseInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("Teacher not found: 99");
    }

    // --- DELETE /teachers/{id} ---

    @Test
    void delete_returns204() throws Exception {
        doNothing().when(teacherService).softDelete(1L);

        mockMvc.perform(delete("/teachers/1"))
                .andExpect(status().isNoContent());

        verify(teacherService).softDelete(1L);
    }

    @Test
    void delete_throwsWhenNotFound() {
        doThrow(new RuntimeException("Teacher not found: 99")).when(teacherService).softDelete(99L);

        assertThatThrownBy(() -> mockMvc.perform(delete("/teachers/99")))
                .hasRootCauseInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("Teacher not found: 99");
    }

    // --- helper ---

    private TeacherResponse response(Long id, String name) {
        return new TeacherResponse(id, name,
                name.toLowerCase().replace(" ", "") + "@meet.com",
                "555-0000", new BigDecimal("45.00"), true);
    }
}
