package com.school.dto;

import com.school.entity.Teacher;

import java.math.BigDecimal;

public record TeacherResponse(
        Long id,
        String name,
        String meetEmail,
        String phone,
        BigDecimal hourlyRate,
        boolean active
) {
    public static TeacherResponse from(Teacher t) {
        return new TeacherResponse(
                t.getId(),
                t.getName(),
                t.getMeetEmail(),
                t.getPhone(),
                t.getHourlyRate(),
                t.isActive()
        );
    }
}
