package com.school.dto;

import com.school.entity.Person;
import com.school.entity.PersonType;

import java.math.BigDecimal;

public record PersonResponse(
        Long id,
        PersonType personType,
        String name,
        String meetEmail,
        String googleUserId,
        String meetDisplayName,
        String classroomEmail,
        String parentEmail,
        String parentPhone,
        String phone,
        BigDecimal hourlyRate,
        boolean active
) {
    public static PersonResponse from(Person p) {
        return new PersonResponse(
                p.getId(),
                p.getPersonType(),
                p.getName(),
                p.getMeetEmail(),
                p.getGoogleUserId(),
                p.getMeetDisplayName(),
                p.getClassroomEmail(),
                p.getParentEmail(),
                p.getParentPhone(),
                p.getPhone(),
                p.getHourlyRate(),
                p.isActive()
        );
    }
}
