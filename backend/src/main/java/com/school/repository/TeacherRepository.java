package com.school.repository;

import com.school.entity.Teacher;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TeacherRepository extends JpaRepository<Teacher, Long> {
    List<Teacher> findByActiveTrue();
    Optional<Teacher> findByMeetEmail(String meetEmail);
    Optional<Teacher> findByGoogleUserId(String googleUserId);
    Optional<Teacher> findByMeetDisplayNameIgnoreCase(String meetDisplayName);
    Optional<Teacher> findByNameIgnoreCase(String name);
}
