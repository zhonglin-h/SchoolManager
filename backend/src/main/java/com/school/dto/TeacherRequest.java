package com.school.dto;

import java.math.BigDecimal;

public record TeacherRequest(
        String name,
        String meetEmail,
        String meetDisplayName,
        String phone,
        BigDecimal hourlyRate
) {}
