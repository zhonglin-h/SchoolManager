package com.school.dto;

import com.school.entity.Student;

public record StudentResponse(
        Long id,
        String name,
        String meetEmail,
        String classroomEmail,
        String parentEmail,
        String parentPhone,
        boolean active
) {
    public static StudentResponse from(Student s) {
        return new StudentResponse(
                s.getId(),
                s.getName(),
                s.getMeetEmail(),
                s.getClassroomEmail(),
                s.getParentEmail(),
                s.getParentPhone(),
                s.isActive()
        );
    }
}
