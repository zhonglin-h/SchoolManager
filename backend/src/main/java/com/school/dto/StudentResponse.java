package com.school.dto;

import com.school.entity.Person;

public record StudentResponse(
        Long id,
        String name,
        String meetEmail,
        String meetDisplayName,
        String classroomEmail,
        String parentEmail,
        String parentPhone,
        boolean active
) {
    public static StudentResponse from(Person s) {
        return new StudentResponse(
                s.getId(),
                s.getName(),
                s.getMeetEmail(),
                s.getMeetDisplayName(),
                s.getClassroomEmail(),
                s.getParentEmail(),
                s.getParentPhone(),
                s.isActive()
        );
    }
}
