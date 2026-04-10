package com.school.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Student {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String meetEmail;

    private String classroomEmail;

    private String parentEmail;

    private String parentPhone;

    /** Populated automatically when a student joins a Google Meet session. */
    private String googleUserId;

    @Builder.Default
    private boolean active = true;
}
