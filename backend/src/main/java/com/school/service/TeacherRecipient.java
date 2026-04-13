package com.school.service;

import com.school.entity.Teacher;

public record TeacherRecipient(Teacher teacher) implements Recipient {
    public Long getId()     { return teacher.getId(); }
    public String getName() { return teacher.getName(); }
}
