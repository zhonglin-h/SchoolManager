package com.school.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Person {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private PersonType personType;

    private String name;

    private String meetEmail;

    private String googleUserId;

    private String meetDisplayName;

    // Student profile fields (v1 on person)
    private String classroomEmail;
    private String parentEmail;
    private String parentPhone;

    // Teacher profile fields (v1 on person)
    private String phone;
    private BigDecimal hourlyRate;

    @Builder.Default
    private boolean active = true;
}
