package com.school.service;

import com.school.entity.Person;

public record PersonSubject(Person person) implements NotificationSubject {
    public Long getId() {
        return person.getId();
    }

    public String getName() {
        return person.getName();
    }
}
