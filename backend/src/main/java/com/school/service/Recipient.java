package com.school.service;

public sealed interface Recipient permits StudentRecipient, TeacherRecipient {
    Long getId();
    String getName();
}
