package com.school.service;

import com.school.entity.Person;

public record StudentSubject(Person student) implements NotificationSubject {
    public Long getId()     { return student.getId(); }
    public String getName() { return student.getName(); }
}
