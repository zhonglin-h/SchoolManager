package com.school.dto;

public record StudentRequest(
        String name,
        String meetEmail,
        String classroomEmail,
        String parentEmail,
        String parentPhone
) {}
