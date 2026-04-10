package com.school.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.school.dto.StudentRequest;
import com.school.dto.StudentResponse;
import com.school.service.StudentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

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

@WebMvcTest(StudentController.class)
class StudentControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean StudentService studentService;

    // --- GET /students ---

    @Test
    void getAll_returnsListOfActiveStudents() throws Exception {
        when(studentService.getAllActive()).thenReturn(List.of(
                response(1L, "Alice"),
                response(2L, "Bob")
        ));

        mockMvc.perform(get("/students"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Alice"))
                .andExpect(jsonPath("$[1].name").value("Bob"));
    }

    @Test
    void getAll_returnsEmptyList() throws Exception {
        when(studentService.getAllActive()).thenReturn(List.of());

        mockMvc.perform(get("/students"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // --- GET /students/{id} ---

    @Test
    void getById_returnsStudent() throws Exception {
        when(studentService.getById(1L)).thenReturn(response(1L, "Alice"));

        mockMvc.perform(get("/students/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Alice"));
    }

    @Test
    void getById_throwsWhenNotFound() {
        when(studentService.getById(99L)).thenThrow(new RuntimeException("Student not found: 99"));

        assertThatThrownBy(() -> mockMvc.perform(get("/students/99")))
                .hasRootCauseInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("Student not found: 99");
    }

    // --- POST /students ---

    @Test
    void create_returnsCreatedStudent() throws Exception {
        StudentRequest req = new StudentRequest("Carol", "carol@meet.com", "carol@class.com",
                "carol-p@test.com", "555-2222");
        when(studentService.create(any(StudentRequest.class))).thenReturn(response(3L, "Carol"));

        mockMvc.perform(post("/students")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(3))
                .andExpect(jsonPath("$.name").value("Carol"));

        verify(studentService).create(any(StudentRequest.class));
    }

    // --- PUT /students/{id} ---

    @Test
    void update_returnsUpdatedStudent() throws Exception {
        StudentRequest req = new StudentRequest("Alice Updated", "alice@meet.com", "alice@class.com",
                "alice-p@test.com", "555-1111");
        when(studentService.update(eq(1L), any(StudentRequest.class)))
                .thenReturn(response(1L, "Alice Updated"));

        mockMvc.perform(put("/students/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Alice Updated"));
    }

    @Test
    void update_throwsWhenNotFound() {
        when(studentService.update(eq(99L), any(StudentRequest.class)))
                .thenThrow(new RuntimeException("Student not found: 99"));

        assertThatThrownBy(() -> mockMvc.perform(put("/students/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new StudentRequest("X", "x@x.com", "x@x.com", "x@x.com", "000")))))
                .hasRootCauseInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("Student not found: 99");
    }

    // --- DELETE /students/{id} ---

    @Test
    void delete_returns204() throws Exception {
        doNothing().when(studentService).softDelete(1L);

        mockMvc.perform(delete("/students/1"))
                .andExpect(status().isNoContent());

        verify(studentService).softDelete(1L);
    }

    @Test
    void delete_throwsWhenNotFound() {
        doThrow(new RuntimeException("Student not found: 99")).when(studentService).softDelete(99L);

        assertThatThrownBy(() -> mockMvc.perform(delete("/students/99")))
                .hasRootCauseInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("Student not found: 99");
    }

    // --- helper ---

    private StudentResponse response(Long id, String name) {
        return new StudentResponse(id, name,
                name.toLowerCase().replace(" ", "") + "@meet.com",
                name.toLowerCase().replace(" ", "") + "@class.com",
                name.toLowerCase().replace(" ", "") + "-p@test.com",
                "555-0000", true);
    }
}
