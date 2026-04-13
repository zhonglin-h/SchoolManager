package com.school.service;

import com.school.entity.Student;

public record StudentRecipient(Student student) implements Recipient {
    public Long getId()     { return student.getId(); }
    public String getName() { return student.getName(); }
}
