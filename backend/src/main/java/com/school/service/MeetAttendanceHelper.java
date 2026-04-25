package com.school.service;

import com.school.entity.Attendance;
import com.school.entity.AttendanceStatus;
import com.school.entity.Person;
import com.school.entity.PersonType;
import com.school.integration.MeetParticipant;
import com.school.model.CalendarEvent;
import com.school.repository.AttendanceRepository;
import com.school.repository.PersonRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

@Slf4j
@Service
class MeetAttendanceHelper {

    private final PersonRepository personRepository;
    private final AttendanceRepository attendanceRepository;
    private final NotificationService notificationService;

    MeetAttendanceHelper(PersonRepository personRepository,
                         AttendanceRepository attendanceRepository,
                         NotificationService notificationService) {
        this.personRepository = personRepository;
        this.attendanceRepository = attendanceRepository;
        this.notificationService = notificationService;
    }

    void processParticipants(CalendarEvent event, List<MeetParticipant> participants,
                             ExpectedParticipants expected, Set<Long> seenStudentIds,
                             Set<Long> seenTeacherIds, Instant lateThreshold) {
        ResolvedParticipants resolved = resolveAndAutoLearn(participants);
        boolean isLate = Instant.now().isAfter(lateThreshold);

        for (Person student : expected.students()) {
            if (!seenStudentIds.contains(student.getId()) && resolved.studentIds().contains(student.getId())) {
                seenStudentIds.add(student.getId());
                AttendanceStatus status = isLate ? AttendanceStatus.LATE : AttendanceStatus.PRESENT;
                recordAttendance(student, event, status);
                notificationService.notify(isLate ? NotificationType.LATE : NotificationType.ARRIVAL, event, new StudentSubject(student));
            }
        }
        for (Person teacher : expected.teachers()) {
            if (!seenTeacherIds.contains(teacher.getId()) && resolved.teacherIds().contains(teacher.getId())) {
                seenTeacherIds.add(teacher.getId());
                AttendanceStatus status = isLate ? AttendanceStatus.LATE : AttendanceStatus.PRESENT;
                recordAttendance(teacher, event, status);
                NotificationType teacherType = isLate ? NotificationType.TEACHER_LATE : NotificationType.TEACHER_ARRIVED;
                notificationService.notify(teacherType, event, new TeacherSubject(teacher));
            }
        }
    }

    ResolvedParticipants resolveAndAutoLearn(List<MeetParticipant> participants) {
        Set<Long> studentIds = new HashSet<>();
        Set<Long> teacherIds = new HashSet<>();

        for (MeetParticipant participant : participants) {
            Optional<Person> student = resolvePersonFromDb(participant, PersonType.STUDENT);
            if (student.isPresent()) {
                Person s = student.get();
                if (autoLearnMeetIdentity(participant,
                        s::getGoogleUserId, s::setGoogleUserId,
                        s::getMeetDisplayName, s::setMeetDisplayName,
                        "student " + s.getName())) {
                    personRepository.save(s);
                }
                studentIds.add(s.getId());
                continue;
            }

            Optional<Person> teacher = resolvePersonFromDb(participant, PersonType.TEACHER);
            if (teacher.isPresent()) {
                Person t = teacher.get();
                if (autoLearnMeetIdentity(participant,
                        t::getGoogleUserId, t::setGoogleUserId,
                        t::getMeetDisplayName, t::setMeetDisplayName,
                        "teacher " + t.getName())) {
                    personRepository.save(t);
                }
                teacherIds.add(t.getId());
            }
        }
        return new ResolvedParticipants(studentIds, teacherIds);
    }

    private Optional<Person> resolvePersonFromDb(MeetParticipant participant, PersonType personType) {
        if (participant.googleUserId() != null) {
            Optional<Person> byUserId = personRepository.findByPersonTypeAndGoogleUserIdAndActiveTrue(personType, participant.googleUserId());
            if (byUserId.isPresent()) {
                return byUserId;
            }
        }
        if (participant.displayName() != null) {
            return personRepository.findByPersonTypeAndMeetDisplayNameIgnoreCaseAndActiveTrue(personType, participant.displayName())
                    .or(() -> personRepository.findByPersonTypeAndNameIgnoreCaseAndActiveTrue(personType, participant.displayName()));
        }
        return Optional.empty();
    }

    private boolean autoLearnMeetIdentity(MeetParticipant participant,
                                          Supplier<String> getUid, Consumer<String> setUid,
                                          Supplier<String> getDisplayName, Consumer<String> setDisplayName,
                                          String entityLabel) {
        boolean changed = false;
        if (participant.googleUserId() != null && getUid.get() == null) {
            setUid.accept(participant.googleUserId());
            changed = true;
        }
        if (participant.displayName() != null && getDisplayName.get() == null) {
            setDisplayName.accept(participant.displayName());
            changed = true;
        }
        if (changed) {
            log.info("Auto-learned Meet identity for {}: userId={}, displayName={}",
                    entityLabel, getUid.get(), getDisplayName.get());
        }
        return changed;
    }

    List<String> findUnmatchedInvitees(CalendarEvent event) {
        if (event.getAttendeeEmails() == null) return List.of();
        List<String> unmatched = new ArrayList<>();
        for (String email : event.getAttendeeEmails()) {
            if (email == null || email.isBlank()) continue;
            boolean found = personRepository.findByMeetEmailAndActiveTrue(email).isPresent();
            if (!found) unmatched.add(email);
        }
        return unmatched;
    }

    List<String> findUnmatchedParticipants(List<MeetParticipant> participants, ExpectedParticipants expected) {
        List<String> unmatched = new ArrayList<>();
        for (MeetParticipant participant : participants) {
            if (participant.displayName() == null || participant.displayName().isBlank()) continue;
            boolean matched = false;
            for (Person student : expected.students()) {
                if (meetIdentityMatches(participant, student.getGoogleUserId(), student.getMeetDisplayName(), student.getName())) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                for (Person teacher : expected.teachers()) {
                    if (meetIdentityMatches(participant, teacher.getGoogleUserId(), teacher.getMeetDisplayName(), teacher.getName())) {
                        matched = true;
                        break;
                    }
                }
            }
            if (!matched) unmatched.add(participant.displayName());
        }
        return unmatched;
    }

    private boolean meetIdentityMatches(MeetParticipant participant, String googleUserId, String meetDisplayName, String name) {
        if (participant.googleUserId() != null && participant.googleUserId().equals(googleUserId)) return true;
        if (participant.displayName() != null && participant.displayName().equalsIgnoreCase(meetDisplayName)) return true;
        return participant.displayName() != null && participant.displayName().equalsIgnoreCase(name);
    }

    ExpectedParticipants getExpectedParticipants(CalendarEvent event) {
        List<Person> students = new ArrayList<>();
        List<Person> teachers = new ArrayList<>();
        if (event.getAttendeeEmails() == null) return new ExpectedParticipants(students, teachers);
        for (String email : event.getAttendeeEmails()) {
            personRepository.findByMeetEmailAndActiveTrue(email).ifPresent(person -> {
                if (person.getPersonType() == PersonType.STUDENT) {
                    students.add(person);
                } else if (person.getPersonType() == PersonType.TEACHER) {
                    teachers.add(person);
                }
            });
        }
        return new ExpectedParticipants(students, teachers);
    }

    void recordAttendance(Person person, CalendarEvent event, AttendanceStatus status) {
        LocalDate today = LocalDate.now();
        Optional<Attendance> existing = attendanceRepository
                .findByPersonIdAndCalendarEventIdAndDate(person.getId(), event.getId(), today);
        if (existing.isEmpty()) {
            attendanceRepository.save(Attendance.builder()
                    .person(person)
                    .calendarEventId(event.getId())
                    .eventTitle(event.getTitle())
                    .date(today)
                    .status(status)
                    .build());
        }
    }

    Instant resolveJoinTime(String googleUserId, String meetDisplayName, String name,
                            Map<String, Instant> byUserId, Map<String, Instant> byDisplayName) {
        if (googleUserId != null && byUserId.containsKey(googleUserId)) return byUserId.get(googleUserId);
        if (meetDisplayName != null && byDisplayName.containsKey(meetDisplayName.toLowerCase())) return byDisplayName.get(meetDisplayName.toLowerCase());
        if (name != null && byDisplayName.containsKey(name.toLowerCase())) return byDisplayName.get(name.toLowerCase());
        return null;
    }
}

record ResolvedParticipants(Set<Long> studentIds, Set<Long> teacherIds) {}
record ExpectedParticipants(List<Person> students, List<Person> teachers) {}
