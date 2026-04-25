package com.school.service;

public sealed interface Recipient permits StudentRecipient, TeacherRecipient, GuestRecipient {
    Long getId();
    String getName();
}
