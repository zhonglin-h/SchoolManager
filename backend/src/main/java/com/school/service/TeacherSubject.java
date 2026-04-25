package com.school.service;

import com.school.entity.Person;

public record TeacherSubject(Person teacher) implements NotificationSubject {
    public Long getId()     { return teacher.getId(); }
    public String getName() { return teacher.getName(); }
}
