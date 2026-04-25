package com.school.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.school.dto.PersonRequest;
import com.school.dto.PersonResponse;
import com.school.entity.PersonType;
import com.school.service.PersonService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PersonController.class)
class PersonControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean PersonService personService;

    @Test
    void getAll_supportsTypeFilter() throws Exception {
        when(personService.getAllActive(PersonType.STUDENT))
                .thenReturn(List.of(new PersonResponse(1L, PersonType.STUDENT, "Alice", "a@meet.com",
                        null, null, null, null, null, null, null, true)));

        mockMvc.perform(get("/people").param("personType", "STUDENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].personType").value("STUDENT"));
    }

    @Test
    void create_returnsSavedPerson() throws Exception {
        PersonRequest req = new PersonRequest(PersonType.TEACHER, "Carol", "c@meet.com",
                null, null, null, null, "555", null);
        when(personService.create(any(PersonRequest.class)))
                .thenReturn(new PersonResponse(5L, PersonType.TEACHER, "Carol", "c@meet.com",
                        null, null, null, null, null, "555", null, true));

        mockMvc.perform(post("/people")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.personType").value("TEACHER"));
    }
}
