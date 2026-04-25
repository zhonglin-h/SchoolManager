package com.school.service;

import com.school.entity.Student;

public record StudentSubject(Student student) implements NotificationSubject {
    public Long getId()     { return student.getId(); }
    public String getName() { return student.getName(); }
}
