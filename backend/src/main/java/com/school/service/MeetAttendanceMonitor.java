package com.school.service;

import com.school.entity.Attendance;
import com.school.entity.AttendanceStatus;
import com.school.entity.Student;
import com.school.entity.Teacher;
import com.school.integration.MeetClient;
import com.school.integration.MeetParticipant;
import com.school.integration.TelegramClient;
import com.school.model.CalendarEvent;
import com.school.repository.AttendanceRepository;
import com.school.repository.StudentRepository;
import com.school.repository.TeacherRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;

@Slf4j
@Service
public class MeetAttendanceMonitor {

    private final CalendarSyncService calendarSyncService;
    private final StudentRepository studentRepository;
    private final TeacherRepository teacherRepository;
    private final AttendanceRepository attendanceRepository;
    private final NotificationService notificationService;
    private final MeetClient googleMeetClient;
    private final ThreadPoolTaskScheduler taskScheduler;
    private final TelegramClient telegramClient;

    @Value("${app.attendance.late-buffer-minutes}")
    private int lateBufferMinutes;

    private final Map<String, ScheduledFuture<?>> pollingFutures = new ConcurrentHashMap<>();
    private final List<ScheduledCheck> upcomingChecks = new CopyOnWriteArrayList<>();

    public record ScheduledCheck(String eventId, String eventTitle, String checkType, Instant scheduledAt) {}

    /** A resolved participant — either a student or a teacher. */
    private record PersonRef(Long id, String type) {}

    public MeetAttendanceMonitor(CalendarSyncService calendarSyncService,
                                  StudentRepository studentRepository,
                                  TeacherRepository teacherRepository,
                                  AttendanceRepository attendanceRepository,
                                  NotificationService notificationService,
                                  MeetClient googleMeetClient,
                                  ThreadPoolTaskScheduler taskScheduler,
                                  TelegramClient telegramClient) {
        this.calendarSyncService = calendarSyncService;
        this.studentRepository = studentRepository;
        this.teacherRepository = teacherRepository;
        this.attendanceRepository = attendanceRepository;
        this.notificationService = notificationService;
        this.googleMeetClient = googleMeetClient;
        this.taskScheduler = taskScheduler;
        this.telegramClient = telegramClient;
    }

    public List<ScheduledCheck> getUpcomingChecks() {
        Instant now = Instant.now();
        return upcomingChecks.stream()
                .filter(c -> c.scheduledAt().isAfter(now))
                .sorted((a, b) -> a.scheduledAt().compareTo(b.scheduledAt()))
                .toList();
    }

    @Scheduled(cron = "0 0 0 * * *")
    public void scheduleDailyRefresh() {
        scheduleEventsForToday();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        scheduleEventsForToday();
    }

    public void scheduleEventsForToday() {
        List<CalendarEvent> events;
        try {
            events = calendarSyncService.getTodaysEvents();
        } catch (Exception e) {
            return;
        }

        upcomingChecks.clear();
        Instant now = Instant.now();

        for (CalendarEvent event : events) {
            Instant minus15 = event.getStartTime().minusMinutes(15)
                    .atZone(ZoneId.systemDefault()).toInstant();
            Instant minus3 = event.getStartTime().minusMinutes(3)
                    .atZone(ZoneId.systemDefault()).toInstant();
            Instant start = event.getStartTime()
                    .atZone(ZoneId.systemDefault()).toInstant();
            Instant end = event.getEndTime()
                    .atZone(ZoneId.systemDefault()).toInstant();

            if (minus15.isAfter(now)) {
                upcomingChecks.add(new ScheduledCheck(event.getId(), event.getTitle(), "MEETING_NOT_STARTED_15", minus15));
                taskScheduler.schedule(() -> {
                    upcomingChecks.removeIf(c -> c.eventId().equals(event.getId()) && c.checkType().equals("MEETING_NOT_STARTED_15"));
                    checkMeetingStarted(event, NotificationType.MEETING_NOT_STARTED_15);
                }, minus15);
            }
            if (minus3.isAfter(now)) {
                upcomingChecks.add(new ScheduledCheck(event.getId(), event.getTitle(), "PRE_CLASS_JOINS", minus3));
                taskScheduler.schedule(() -> {
                    upcomingChecks.removeIf(c -> c.eventId().equals(event.getId()) && c.checkType().equals("PRE_CLASS_JOINS"));
                    checkPreClassJoins(event);
                }, minus3);
            }
            if (start.isAfter(now)) {
                upcomingChecks.add(new ScheduledCheck(event.getId(), event.getTitle(), "SESSION_START", start));
                taskScheduler.schedule(() -> {
                    upcomingChecks.removeIf(c -> c.eventId().equals(event.getId()) && c.checkType().equals("SESSION_START"));
                    startSessionPolling(event);
                }, start);
            }
            if (end.isAfter(now)) {
                upcomingChecks.add(new ScheduledCheck(event.getId(), event.getTitle(), "SESSION_FINALIZE", end));
                taskScheduler.schedule(() -> {
                    upcomingChecks.removeIf(c -> c.eventId().equals(event.getId()) && c.checkType().equals("SESSION_FINALIZE"));
                    finalizeSession(event);
                }, end);
            }
        }
    }

    public void checkMeetingStarted(CalendarEvent event, NotificationType type) {
        try {
            boolean active = googleMeetClient.isMeetingActive(event.getSpaceCode());
            if (!active) {
                notificationService.notify(type, event, null);
            }
        } catch (Exception e) {
            log.warn("Failed to check meeting started for {}: {}", event.getId(), e.getMessage());
        }
    }

    public void checkPreClassJoins(CalendarEvent event) {
        try {
            List<MeetParticipant> participants = googleMeetClient.getActiveParticipants(event.getSpaceCode());
            List<Student> expectedStudents = getExpectedStudents(event);
            Set<Long> presentStudentIds = resolveStudentIds(resolveAndAutoLearn(participants));
            for (Student student : expectedStudents) {
                if (!presentStudentIds.contains(student.getId())) {
                    notificationService.notify(NotificationType.NOT_YET_JOINED_3, event, student);
                }
            }
        } catch (Exception e) {
            log.warn("Failed pre-class join check for {}: {}", event.getId(), e.getMessage());
        }
    }

    public void startSessionPolling(CalendarEvent event) {
        List<Student> expectedStudents = getExpectedStudents(event);
        List<Teacher> expectedTeachers = getExpectedTeachers(event);
        int totalExpected = expectedStudents.size() + expectedTeachers.size();
        Set<Long> seenStudentIds = new HashSet<>();
        Set<Long> seenTeacherIds = new HashSet<>();
        Instant classStart = event.getStartTime().atZone(ZoneId.systemDefault()).toInstant();
        Instant lateThreshold = classStart.plusSeconds(lateBufferMinutes * 60L);
        boolean[] meetingActive = {false};

        // Initial snapshot at class start time
        try {
            if (googleMeetClient.isMeetingActive(event.getSpaceCode())) {
                meetingActive[0] = true;
                List<MeetParticipant> participants = googleMeetClient.getActiveParticipants(event.getSpaceCode());
                Set<PersonRef> presentRefs = resolveAndAutoLearn(participants);
                Set<Long> presentStudentIds = resolveStudentIds(presentRefs);
                Set<Long> presentTeacherIds = resolveTeacherIds(presentRefs);

                for (Student student : expectedStudents) {
                    if (presentStudentIds.contains(student.getId())) {
                        seenStudentIds.add(student.getId());
                        recordStudentAttendance(student, event, AttendanceStatus.PRESENT);
                        notificationService.notify(NotificationType.ARRIVAL, event, student);
                    }
                }
                for (Teacher teacher : expectedTeachers) {
                    if (presentTeacherIds.contains(teacher.getId())) {
                        seenTeacherIds.add(teacher.getId());
                        recordTeacherAttendance(teacher, event, AttendanceStatus.PRESENT);
                    }
                }
            } else {
                sendMeetingStartReminder(event);
            }
        } catch (Exception e) {
            log.warn("Failed session start poll for {}: {}", event.getId(), e.getMessage());
        }

        ScheduledFuture<?>[] futureHolder = new ScheduledFuture<?>[1];
        futureHolder[0] = taskScheduler.scheduleAtFixedRate(() -> {
            try {
                // While meeting not yet started, check every tick and remind via Telegram
                if (!meetingActive[0]) {
                    try {
                        if (!googleMeetClient.isMeetingActive(event.getSpaceCode())) {
                            sendMeetingStartReminder(event);
                            return;
                        }
                        meetingActive[0] = true;
                    } catch (Exception e) {
                        log.warn("Failed to check meeting active for {}: {}", event.getId(), e.getMessage());
                        return;
                    }
                }

                List<MeetParticipant> participants = googleMeetClient.getActiveParticipants(event.getSpaceCode());
                Set<PersonRef> presentRefs = resolveAndAutoLearn(participants);
                Set<Long> presentStudentIds = resolveStudentIds(presentRefs);
                Set<Long> presentTeacherIds = resolveTeacherIds(presentRefs);

                for (Student student : expectedStudents) {
                    if (!seenStudentIds.contains(student.getId()) && presentStudentIds.contains(student.getId())) {
                        seenStudentIds.add(student.getId());
                        if (Instant.now().isAfter(lateThreshold)) {
                            recordStudentAttendance(student, event, AttendanceStatus.LATE);
                            notificationService.notify(NotificationType.LATE, event, student);
                        } else {
                            recordStudentAttendance(student, event, AttendanceStatus.PRESENT);
                            notificationService.notify(NotificationType.ARRIVAL, event, student);
                        }
                    }
                }
                for (Teacher teacher : expectedTeachers) {
                    if (!seenTeacherIds.contains(teacher.getId()) && presentTeacherIds.contains(teacher.getId())) {
                        seenTeacherIds.add(teacher.getId());
                        if (Instant.now().isAfter(lateThreshold)) {
                            recordTeacherAttendance(teacher, event, AttendanceStatus.LATE);
                        } else {
                            recordTeacherAttendance(teacher, event, AttendanceStatus.PRESENT);
                        }
                    }
                }
                if (seenStudentIds.size() + seenTeacherIds.size() >= totalExpected && totalExpected > 0) {
                    notificationService.notify(NotificationType.ALL_PRESENT, event, null);
                    ScheduledFuture<?> future = pollingFutures.remove(event.getId());
                    if (future != null) future.cancel(false);
                }
            } catch (Exception e) {
                log.warn("Failed polling for {}: {}", event.getId(), e.getMessage());
            }
        }, java.time.Duration.ofSeconds(60));

        pollingFutures.put(event.getId(), futureHolder[0]);
    }

    private void sendMeetingStartReminder(CalendarEvent event) {
        String message = "⚠️ Reminder: The Google Meet session for \"" + event.getTitle()
                + "\" has not been started yet. Please start the meeting.";
        try {
            telegramClient.send(message);
            log.info("Sent meeting-not-started Telegram reminder for event '{}'", event.getTitle());
        } catch (Exception e) {
            log.warn("Failed to send meeting-not-started Telegram reminder for {}: {}",
                    event.getId(), e.getMessage());
        }
    }

    public void finalizeSession(CalendarEvent event) {
        ScheduledFuture<?> future = pollingFutures.remove(event.getId());
        if (future != null) future.cancel(false);

        Instant classStart = event.getStartTime().atZone(ZoneId.systemDefault()).toInstant();
        LocalDate today = LocalDate.now();

        // Build a join-time map from the full participant history (including those who left)
        Map<String, Instant> joinTimeByUserId = new java.util.HashMap<>();
        Map<String, Instant> joinTimeByDisplayName = new java.util.HashMap<>();
        try {
            List<MeetParticipant> allParticipants = googleMeetClient.getAllParticipants(event.getSpaceCode());
            for (MeetParticipant p : allParticipants) {
                if (p.earliestStartTime() == null) continue;
                if (p.googleUserId() != null) joinTimeByUserId.put(p.googleUserId(), p.earliestStartTime());
                if (p.displayName() != null) joinTimeByDisplayName.put(p.displayName().toLowerCase(), p.earliestStartTime());
            }
        } catch (Exception e) {
            log.warn("Failed to fetch all participants for finalize {}: {}", event.getId(), e.getMessage());
        }

        for (Student student : getExpectedStudents(event)) {
            Optional<Attendance> existing = attendanceRepository
                    .findByStudentIdAndCalendarEventIdAndDate(student.getId(), event.getId(), today);
            if (existing.isEmpty()) {
                Instant joinTime = resolveJoinTime(student.getGoogleUserId(), student.getMeetDisplayName(),
                        student.getName(), joinTimeByUserId, joinTimeByDisplayName);
                if (joinTime == null) {
                    recordStudentAttendance(student, event, AttendanceStatus.ABSENT);
                    notificationService.notify(NotificationType.ABSENT, event, student);
                } else if (joinTime.isAfter(classStart.plusSeconds(lateBufferMinutes * 60L))) {
                    recordStudentAttendance(student, event, AttendanceStatus.LATE);
                    notificationService.notify(NotificationType.LATE, event, student);
                } else {
                    recordStudentAttendance(student, event, AttendanceStatus.PRESENT);
                }
            }
        }
        for (Teacher teacher : getExpectedTeachers(event)) {
            Optional<Attendance> existing = attendanceRepository
                    .findByTeacherIdAndCalendarEventIdAndDate(teacher.getId(), event.getId(), today);
            if (existing.isEmpty()) {
                Instant joinTime = resolveJoinTime(teacher.getGoogleUserId(), teacher.getMeetDisplayName(),
                        teacher.getName(), joinTimeByUserId, joinTimeByDisplayName);
                if (joinTime == null) {
                    recordTeacherAttendance(teacher, event, AttendanceStatus.ABSENT);
                } else if (joinTime.isAfter(classStart.plusSeconds(lateBufferMinutes * 60L))) {
                    recordTeacherAttendance(teacher, event, AttendanceStatus.LATE);
                } else {
                    recordTeacherAttendance(teacher, event, AttendanceStatus.PRESENT);
                }
            }
        }
    }

    private Instant resolveJoinTime(String googleUserId, String meetDisplayName, String name,
                                    Map<String, Instant> byUserId, Map<String, Instant> byDisplayName) {
        if (googleUserId != null && byUserId.containsKey(googleUserId)) return byUserId.get(googleUserId);
        if (meetDisplayName != null && byDisplayName.containsKey(meetDisplayName.toLowerCase())) return byDisplayName.get(meetDisplayName.toLowerCase());
        if (name != null && byDisplayName.containsKey(name.toLowerCase())) return byDisplayName.get(name.toLowerCase());
        return null;
    }

    /**
     * Resolves Meet participants to student/teacher IDs.
     * Matching priority (per person type): googleUserId → meetDisplayName → name.
     * Auto-learns googleUserId and meetDisplayName on first match.
     */
    private Set<PersonRef> resolveAndAutoLearn(List<MeetParticipant> participants) {
        Set<PersonRef> matched = new HashSet<>();
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
                autoLearnStudent(student.get(), participant);
                matched.add(new PersonRef(student.get().getId(), "STUDENT"));
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
                autoLearnTeacher(teacher.get(), participant);
                matched.add(new PersonRef(teacher.get().getId(), "TEACHER"));
            }
        }
        return matched;
    }

    private void autoLearnStudent(Student student, MeetParticipant participant) {
        boolean changed = false;
        if (participant.googleUserId() != null && student.getGoogleUserId() == null) {
            student.setGoogleUserId(participant.googleUserId());
            changed = true;
        }
        if (participant.displayName() != null && student.getMeetDisplayName() == null) {
            student.setMeetDisplayName(participant.displayName());
            changed = true;
        }
        if (changed) {
            studentRepository.save(student);
            log.info("Auto-learned Meet identity for student '{}': userId={}, displayName={}",
                    student.getName(), student.getGoogleUserId(), student.getMeetDisplayName());
        }
    }

    private void autoLearnTeacher(Teacher teacher, MeetParticipant participant) {
        boolean changed = false;
        if (participant.googleUserId() != null && teacher.getGoogleUserId() == null) {
            teacher.setGoogleUserId(participant.googleUserId());
            changed = true;
        }
        if (participant.displayName() != null && teacher.getMeetDisplayName() == null) {
            teacher.setMeetDisplayName(participant.displayName());
            changed = true;
        }
        if (changed) {
            teacherRepository.save(teacher);
            log.info("Auto-learned Meet identity for teacher '{}': userId={}, displayName={}",
                    teacher.getName(), teacher.getGoogleUserId(), teacher.getMeetDisplayName());
        }
    }

    private static Set<Long> resolveStudentIds(Set<PersonRef> refs) {
        Set<Long> ids = new HashSet<>();
        for (PersonRef ref : refs) {
            if ("STUDENT".equals(ref.type())) ids.add(ref.id());
        }
        return ids;
    }

    private static Set<Long> resolveTeacherIds(Set<PersonRef> refs) {
        Set<Long> ids = new HashSet<>();
        for (PersonRef ref : refs) {
            if ("TEACHER".equals(ref.type())) ids.add(ref.id());
        }
        return ids;
    }

    private List<Student> getExpectedStudents(CalendarEvent event) {
        List<Student> students = new ArrayList<>();
        if (event.getAttendeeEmails() == null) return students;
        for (String email : event.getAttendeeEmails()) {
            studentRepository.findByMeetEmailAndActiveTrue(email).ifPresent(students::add);
        }
        return students;
    }

    private List<Teacher> getExpectedTeachers(CalendarEvent event) {
        List<Teacher> teachers = new ArrayList<>();
        if (event.getAttendeeEmails() == null) return teachers;
        for (String email : event.getAttendeeEmails()) {
            teacherRepository.findByMeetEmailAndActiveTrue(email).ifPresent(teachers::add);
        }
        return teachers;
    }

    private void recordStudentAttendance(Student student, CalendarEvent event, AttendanceStatus status) {
        LocalDate today = LocalDate.now();
        Optional<Attendance> existing = attendanceRepository
                .findByStudentIdAndCalendarEventIdAndDate(student.getId(), event.getId(), today);
        if (existing.isEmpty()) {
            attendanceRepository.save(Attendance.builder()
                    .student(student)
                    .calendarEventId(event.getId())
                    .date(today)
                    .status(status)
                    .build());
        }
    }

    private void recordTeacherAttendance(Teacher teacher, CalendarEvent event, AttendanceStatus status) {
        LocalDate today = LocalDate.now();
        Optional<Attendance> existing = attendanceRepository
                .findByTeacherIdAndCalendarEventIdAndDate(teacher.getId(), event.getId(), today);
        if (existing.isEmpty()) {
            attendanceRepository.save(Attendance.builder()
                    .teacher(teacher)
                    .calendarEventId(event.getId())
                    .date(today)
                    .status(status)
                    .build());
        }
    }
}
