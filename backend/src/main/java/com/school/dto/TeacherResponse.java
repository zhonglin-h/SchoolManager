package com.school.dto;

import com.school.entity.Person;

import java.math.BigDecimal;

public record TeacherResponse(
        Long id,
        String name,
        String meetEmail,
        String meetDisplayName,
        String phone,
        BigDecimal hourlyRate,
        boolean active
) {
    public static TeacherResponse from(Person t) {
        return new TeacherResponse(
                t.getId(),
                t.getName(),
                t.getMeetEmail(),
                t.getMeetDisplayName(),
                t.getPhone(),
                t.getHourlyRate(),
                t.isActive()
        );
    }
}
