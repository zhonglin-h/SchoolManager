package com.school.dto;

import com.school.entity.PersonType;

import java.math.BigDecimal;

public record PersonRequest(
        PersonType personType,
        String name,
        String meetEmail,
        String meetDisplayName,
        String classroomEmail,
        String parentEmail,
        String parentPhone,
        String phone,
        BigDecimal hourlyRate
) {}
