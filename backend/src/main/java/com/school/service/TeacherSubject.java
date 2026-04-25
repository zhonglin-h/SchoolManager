package com.school.service;

import com.school.entity.Teacher;

public record TeacherSubject(Teacher teacher) implements NotificationSubject {
    public Long getId()     { return teacher.getId(); }
    public String getName() { return teacher.getName(); }
}
