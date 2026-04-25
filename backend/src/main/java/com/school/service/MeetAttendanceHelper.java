package com.school.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;

import com.school.entity.Attendance;
import com.school.entity.AttendanceStatus;
import com.school.entity.Student;
import com.school.entity.Teacher;
import com.school.integration.MeetParticipant;
import com.school.model.CalendarEvent;
import com.school.repository.AttendanceRepository;
import com.school.repository.StudentRepository;
import com.school.repository.TeacherRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Pure helper service: participant resolution, attendance persistence, and Meet identity
 * auto-learning. Contains no scheduling logic — all side-effect dispatch lives in callers.
 */
@Slf4j
@Service
class MeetAttendanceHelper {

    private final StudentRepository studentRepository;
    private final TeacherRepository teacherRepository;
    private final AttendanceRepository attendanceRepository;
    private final NotificationService notificationService;

    MeetAttendanceHelper(StudentRepository studentRepository,
                          TeacherRepository teacherRepository,
                          AttendanceRepository attendanceRepository,
                          NotificationService notificationService) {
        this.studentRepository = studentRepository;
        this.teacherRepository = teacherRepository;
        this.attendanceRepository = attendanceRepository;
        this.notificationService = notificationService;
    }

    /**
     * Resolves participants and records attendance for any newly-seen students and teachers.
     * Uses the lateThreshold to determine PRESENT vs LATE status.
     */
    void processParticipants(CalendarEvent event, List<MeetParticipant> participants,
                              ExpectedParticipants expected, Set<Long> seenStudentIds,
                              Set<Long> seenTeacherIds, Instant lateThreshold) {
        ResolvedParticipants resolved = resolveAndAutoLearn(participants);
        boolean isLate = Instant.now().isAfter(lateThreshold);

        for (Student student : expected.students()) {
            if (!seenStudentIds.contains(student.getId()) && resolved.studentIds().contains(student.getId())) {
                seenStudentIds.add(student.getId());
                AttendanceStatus status = isLate ? AttendanceStatus.LATE : AttendanceStatus.PRESENT;
                recordStudentAttendance(student, event, status);
                notificationService.notify(isLate ? NotificationType.LATE : NotificationType.ARRIVAL, event, new StudentRecipient(student));
            }
        }
        for (Teacher teacher : expected.teachers()) {
            if (!seenTeacherIds.contains(teacher.getId()) && resolved.teacherIds().contains(teacher.getId())) {
                seenTeacherIds.add(teacher.getId());
                AttendanceStatus status = isLate ? AttendanceStatus.LATE : AttendanceStatus.PRESENT;
                recordTeacherAttendance(teacher, event, status);
                NotificationType teacherType = isLate ? NotificationType.TEACHER_LATE : NotificationType.TEACHER_ARRIVED;
                notificationService.notify(teacherType, event, new TeacherRecipient(teacher));
            }
        }
    }

    /**
     * Resolves Meet participants to student/teacher IDs.
     * Matching priority (per person type): googleUserId → meetDisplayName → name.
     * Auto-learns googleUserId and meetDisplayName on first match.
     */
    ResolvedParticipants resolveAndAutoLearn(List<MeetParticipant> participants) {
        Set<Long> studentIds = new HashSet<>();
        Set<Long> teacherIds = new HashSet<>();

        for (MeetParticipant participant : participants) {
            Optional<Student> student = Optional.empty();
            if (participant.googleUserId() != null) {
                student = studentRepository.findByGoogleUserIdAndActiveTrue(participant.googleUserId());
            }
            if (student.isEmpty() && participant.displayName() != null) {
                student = studentRepository.findByMeetDisplayNameIgnoreCaseAndActiveTrue(participant.displayName())
                        .or(() -> studentRepository.findByNameIgnoreCaseAndActiveTrue(participant.displayName()));
            }
            if (student.isPresent()) {
                if (autoLearnMeetIdentity(participant,
                        student.get()::getGoogleUserId, student.get()::setGoogleUserId,
                        student.get()::getMeetDisplayName, student.get()::setMeetDisplayName,
                        "student " + student.get().getName())) {
                    studentRepository.save(student.get());
                }
                studentIds.add(student.get().getId());
                continue;
            }

            Optional<Teacher> teacher = Optional.empty();
            if (participant.googleUserId() != null) {
                teacher = teacherRepository.findByGoogleUserIdAndActiveTrue(participant.googleUserId());
            }
            if (teacher.isEmpty() && participant.displayName() != null) {
                teacher = teacherRepository.findByMeetDisplayNameIgnoreCaseAndActiveTrue(participant.displayName())
                        .or(() -> teacherRepository.findByNameIgnoreCaseAndActiveTrue(participant.displayName()));
            }
            if (teacher.isPresent()) {
                if (autoLearnMeetIdentity(participant,
                        teacher.get()::getGoogleUserId, teacher.get()::setGoogleUserId,
                        teacher.get()::getMeetDisplayName, teacher.get()::setMeetDisplayName,
                        "teacher " + teacher.get().getName())) {
                    teacherRepository.save(teacher.get());
                }
                teacherIds.add(teacher.get().getId());
            }
        }
        return new ResolvedParticipants(studentIds, teacherIds);
    }

    /**
     * Learns googleUserId and meetDisplayName from a Meet participant if not already stored.
     * Returns true if any field was updated (caller should persist the entity).
     */
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

    /**
     * Returns attendee emails from the event that do not match any student or teacher
     * with the same {@code meetEmail} in the database. Re-fetched on every call.
     */
    List<String> findUnmatchedInvitees(CalendarEvent event) {
        if (event.getAttendeeEmails() == null) return List.of();
        List<String> unmatched = new ArrayList<>();
        for (String email : event.getAttendeeEmails()) {
            if (email == null || email.isBlank()) continue;
            boolean found = studentRepository.findByMeetEmailAndActiveTrue(email).isPresent()
                    || teacherRepository.findByMeetEmailAndActiveTrue(email).isPresent();
            if (!found) unmatched.add(email);
        }
        return unmatched;
    }

    /**
     * Returns the display names of active participants who cannot be matched to any expected
     * student or teacher using the standard priority chain (googleUserId → meetDisplayName → name).
     * Participants with blank/null display names are skipped.
     */
    List<String> findUnmatchedParticipants(List<MeetParticipant> participants, ExpectedParticipants expected) {
        List<String> unmatched = new ArrayList<>();
        for (MeetParticipant participant : participants) {
            if (participant.displayName() == null || participant.displayName().isBlank()) continue;
            boolean matched = false;
            for (com.school.entity.Student student : expected.students()) {
                if (meetIdentityMatches(participant, student.getGoogleUserId(),
                        student.getMeetDisplayName(), student.getName())) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                for (com.school.entity.Teacher teacher : expected.teachers()) {
                    if (meetIdentityMatches(participant, teacher.getGoogleUserId(),
                            teacher.getMeetDisplayName(), teacher.getName())) {
                        matched = true;
                        break;
                    }
                }
            }
            if (!matched) unmatched.add(participant.displayName());
        }
        return unmatched;
    }

    private boolean meetIdentityMatches(MeetParticipant participant, String googleUserId,
                                        String meetDisplayName, String name) {
        if (participant.googleUserId() != null && participant.googleUserId().equals(googleUserId)) return true;
        if (participant.displayName() != null && participant.displayName().equalsIgnoreCase(meetDisplayName)) return true;
        if (participant.displayName() != null && participant.displayName().equalsIgnoreCase(name)) return true;
        return false;
    }

    /** Looks up the enrolled students and assigned teacher for an event via their Meet email addresses. */
    ExpectedParticipants getExpectedParticipants(CalendarEvent event) {
        List<Student> students = new ArrayList<>();
        List<Teacher> teachers = new ArrayList<>();
        if (event.getAttendeeEmails() == null) return new ExpectedParticipants(students, teachers);
        for (String email : event.getAttendeeEmails()) {
            studentRepository.findByMeetEmailAndActiveTrue(email).ifPresent(students::add);
            teacherRepository.findByMeetEmailAndActiveTrue(email).ifPresent(teachers::add);
        }
        return new ExpectedParticipants(students, teachers);
    }

    /** Persists a student attendance record only if one does not already exist for today's session. */
    void recordStudentAttendance(Student student, CalendarEvent event, AttendanceStatus status) {
        LocalDate today = LocalDate.now();
        Optional<Attendance> existing = attendanceRepository
                .findByStudentIdAndCalendarEventIdAndDate(student.getId(), event.getId(), today);
        if (existing.isEmpty()) {
            attendanceRepository.save(Attendance.builder()
                    .student(student)
                    .calendarEventId(event.getId())
                    .eventTitle(event.getTitle())
                    .date(today)
                    .status(status)
                    .build());
        }
    }

    /** Persists a teacher attendance record only if one does not already exist for today's session. */
    void recordTeacherAttendance(Teacher teacher, CalendarEvent event, AttendanceStatus status) {
        LocalDate today = LocalDate.now();
        Optional<Attendance> existing = attendanceRepository
                .findByTeacherIdAndCalendarEventIdAndDate(teacher.getId(), event.getId(), today);
        if (existing.isEmpty()) {
            attendanceRepository.save(Attendance.builder()
                    .teacher(teacher)
                    .calendarEventId(event.getId())
                    .eventTitle(event.getTitle())
                    .date(today)
                    .status(status)
                    .build());
        }
    }

    /** Returns the earliest join time for a person, trying googleUserId → displayName → name. */
    Instant resolveJoinTime(String googleUserId, String meetDisplayName, String name,
                             Map<String, Instant> byUserId, Map<String, Instant> byDisplayName) {
        if (googleUserId != null && byUserId.containsKey(googleUserId)) return byUserId.get(googleUserId);
        if (meetDisplayName != null && byDisplayName.containsKey(meetDisplayName.toLowerCase())) return byDisplayName.get(meetDisplayName.toLowerCase());
        if (name != null && byDisplayName.containsKey(name.toLowerCase())) return byDisplayName.get(name.toLowerCase());
        return null;
    }
}

// Package-private data carriers shared between MeetAttendanceHelper and MeetSessionHandler
record ResolvedParticipants(Set<Long> studentIds, Set<Long> teacherIds) {}
record ExpectedParticipants(List<Student> students, List<Teacher> teachers) {}
