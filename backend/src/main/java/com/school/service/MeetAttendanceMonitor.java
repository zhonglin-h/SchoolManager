package com.school.service;

import com.school.entity.Attendance;
import com.school.entity.AttendanceStatus;
import com.school.entity.Student;
import com.school.entity.Teacher;
import com.school.integration.MeetClient;
import com.school.integration.MeetParticipant;
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

import java.time.Duration;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

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

    @Value("${app.attendance.late-buffer-minutes}")
    private int lateBufferMinutes;

    private final Map<String, ScheduledFuture<?>> pollingFutures = new ConcurrentHashMap<>();
    private final Map<String, List<ScheduledFuture<?>>> oneTimeFutures = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastScheduledStartTime = new ConcurrentHashMap<>();
    private final List<ScheduledCheck> upcomingChecks = new CopyOnWriteArrayList<>();

    public record ScheduledCheck(String eventId, String eventTitle, String checkType, Instant scheduledAt) {}

    private record ResolvedParticipants(Set<Long> studentIds, Set<Long> teacherIds) {}
    private record ExpectedParticipants(List<Student> students, List<Teacher> teachers) {}

    public MeetAttendanceMonitor(CalendarSyncService calendarSyncService,
                                  StudentRepository studentRepository,
                                  TeacherRepository teacherRepository,
                                  AttendanceRepository attendanceRepository,
                                  NotificationService notificationService,
                                  MeetClient googleMeetClient,
                                  ThreadPoolTaskScheduler taskScheduler) {
        this.calendarSyncService = calendarSyncService;
        this.studentRepository = studentRepository;
        this.teacherRepository = teacherRepository;
        this.attendanceRepository = attendanceRepository;
        this.notificationService = notificationService;
        this.googleMeetClient = googleMeetClient;
        this.taskScheduler = taskScheduler;
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

        // Build the set of IDs that are still on today's calendar after this sync
        Set<String> freshEventIds = new HashSet<>();
        for (CalendarEvent e : events) {
            freshEventIds.add(e.getId());
        }

        // Cancel futures for events that were removed from or moved off today's calendar
        // Copy keyset to avoid ConcurrentModificationException while removing stale entries
        for (String staleId : new ArrayList<>(oneTimeFutures.keySet())) {
            if (!freshEventIds.contains(staleId)) {
                cancelOneTimeFutures(staleId);
                ScheduledFuture<?> pf = pollingFutures.remove(staleId);
                if (pf != null) pf.cancel(false);
                lastScheduledStartTime.remove(staleId);
            }
        }

        upcomingChecks.clear();
        Instant now = Instant.now();

        for (CalendarEvent event : events) {
            // Cancel any existing one-shot futures for this event (prevents duplicates on re-sync)
            cancelOneTimeFutures(event.getId());

            // Cancel active polling future — will be restarted by the new SESSION_START task
            ScheduledFuture<?> existingPolling = pollingFutures.remove(event.getId());
            if (existingPolling != null) existingPolling.cancel(false);

            // Detect reschedule: if the start time changed, clear today's notification logs
            // so notifications can re-fire at the correct new time
            Instant newStart = event.getStartTime().atZone(ZoneId.systemDefault()).toInstant();
            Instant previousStart = lastScheduledStartTime.get(event.getId());
            if (previousStart != null && !previousStart.equals(newStart)) {
                notificationService.clearTodayLogsForEvent(event.getId());
                log.info("Event '{}' rescheduled; cleared today's notification logs", event.getTitle());
            }
            lastScheduledStartTime.put(event.getId(), newStart);

            List<ScheduledFuture<?>> futures = new ArrayList<>();

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
                futures.add(taskScheduler.schedule(() -> {
                    upcomingChecks.removeIf(c -> c.eventId().equals(event.getId()) && c.checkType().equals("MEETING_NOT_STARTED_15"));
                    checkMeetingStarted(event, NotificationType.MEETING_NOT_STARTED_15);
                }, minus15));
            }
            if (minus3.isAfter(now)) {
                upcomingChecks.add(new ScheduledCheck(event.getId(), event.getTitle(), "PRE_CLASS_JOINS", minus3));
                futures.add(taskScheduler.schedule(() -> {
                    upcomingChecks.removeIf(c -> c.eventId().equals(event.getId()) && c.checkType().equals("PRE_CLASS_JOINS"));
                    checkPreClassJoins(event);
                }, minus3));
            }
            if (start.isAfter(now)) {
                upcomingChecks.add(new ScheduledCheck(event.getId(), event.getTitle(), "SESSION_START", start));
                futures.add(taskScheduler.schedule(() -> {
                    upcomingChecks.removeIf(c -> c.eventId().equals(event.getId()) && c.checkType().equals("SESSION_START"));
                    startSessionPolling(event);
                }, start));
            }
            if (end.isAfter(now)) {
                upcomingChecks.add(new ScheduledCheck(event.getId(), event.getTitle(), "SESSION_FINALIZE", end));
                futures.add(taskScheduler.schedule(() -> {
                    upcomingChecks.removeIf(c -> c.eventId().equals(event.getId()) && c.checkType().equals("SESSION_FINALIZE"));
                    finalizeSession(event);
                }, end));
            }

            oneTimeFutures.put(event.getId(), futures);
        }
    }

    /**
     * Cancels all one-time scheduled check futures for the given event and removes them from
     * the tracking map. Called before rescheduling an event and when an event is removed from
     * today's calendar, to prevent stale tasks from firing at outdated times.
     */
    private void cancelOneTimeFutures(String eventId) {
        List<ScheduledFuture<?>> futures = oneTimeFutures.remove(eventId);
        if (futures != null) {
            futures.forEach(f -> f.cancel(false));
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
            ExpectedParticipants expected = getExpectedParticipants(event);
            Set<Long> presentStudentIds = resolveAndAutoLearn(participants).studentIds();
            for (Student student : expected.students()) {
                if (!presentStudentIds.contains(student.getId())) {
                    notificationService.notify(NotificationType.NOT_YET_JOINED_3, event, new StudentRecipient(student));
                }
            }
        } catch (Exception e) {
            log.warn("Failed pre-class join check for {}: {}", event.getId(), e.getMessage());
        }
    }

    public void startSessionPolling(CalendarEvent event) {
        ExpectedParticipants expected = getExpectedParticipants(event);
        int totalExpected = expected.students().size() + expected.teachers().size();
        Set<Long> seenStudentIds = new HashSet<>();
        Set<Long> seenTeacherIds = new HashSet<>();
        Instant lateThreshold = event.getStartTime().atZone(ZoneId.systemDefault()).toInstant()
                .plusSeconds(lateBufferMinutes * 60L);
        AtomicBoolean meetingActive = new AtomicBoolean(false);
        AtomicReference<ScheduledFuture<?>> futureRef = new AtomicReference<>();

        // Initial snapshot at class start time
        try {
            if (googleMeetClient.isMeetingActive(event.getSpaceCode())) {
                meetingActive.set(true);
                processParticipants(event, googleMeetClient.getActiveParticipants(event.getSpaceCode()),
                        expected, seenStudentIds, seenTeacherIds, lateThreshold);
            } else {
                sendMeetingStartReminder(event);
            }
        } catch (Exception e) {
            log.warn("Failed session start poll for {}: {}", event.getId(), e.getMessage());
        }

        futureRef.set(taskScheduler.scheduleAtFixedRate(() -> {
            try {
                // While meeting not yet started, check every tick and remind via Telegram
                if (!meetingActive.get()) {
                    try {
                        if (!googleMeetClient.isMeetingActive(event.getSpaceCode())) {
                            sendMeetingStartReminder(event);
                            return;
                        }
                        meetingActive.set(true);
                    } catch (Exception e) {
                        log.warn("Failed to check meeting active for {}: {}", event.getId(), e.getMessage());
                        return;
                    }
                }

                processParticipants(event, googleMeetClient.getActiveParticipants(event.getSpaceCode()),
                        expected, seenStudentIds, seenTeacherIds, lateThreshold);

                if (seenStudentIds.size() + seenTeacherIds.size() >= totalExpected && totalExpected > 0) {
                    notificationService.notify(NotificationType.ALL_PRESENT, event, null);
                    ScheduledFuture<?> f = futureRef.get();
                    if (f != null) f.cancel(false);
                }
            } catch (Exception e) {
                log.warn("Failed polling for {}: {}", event.getId(), e.getMessage());
            }
        }, Duration.ofSeconds(60)));

        pollingFutures.put(event.getId(), futureRef.get());
    }

    /**
     * Resolves participants and records attendance for any newly-seen students and teachers.
     * Uses the lateThreshold to determine PRESENT vs LATE status.
     */
    private void processParticipants(CalendarEvent event, List<MeetParticipant> participants,
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

    private void sendMeetingStartReminder(CalendarEvent event) {
        notificationService.notify(NotificationType.MEETING_NOT_STARTED_15, event, null);
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

        ExpectedParticipants expected = getExpectedParticipants(event);

        for (Student student : expected.students()) {
            Optional<Attendance> existing = attendanceRepository
                    .findByStudentIdAndCalendarEventIdAndDate(student.getId(), event.getId(), today);
            if (existing.isEmpty()) {
                Instant joinTime = resolveJoinTime(student.getGoogleUserId(), student.getMeetDisplayName(),
                        student.getName(), joinTimeByUserId, joinTimeByDisplayName);
                if (joinTime == null) {
                    recordStudentAttendance(student, event, AttendanceStatus.ABSENT);
                    notificationService.notify(NotificationType.ABSENT, event, new StudentRecipient(student));
                } else if (joinTime.isAfter(classStart.plusSeconds(lateBufferMinutes * 60L))) {
                    recordStudentAttendance(student, event, AttendanceStatus.LATE);
                    notificationService.notify(NotificationType.LATE, event, new StudentRecipient(student));
                } else {
                    recordStudentAttendance(student, event, AttendanceStatus.PRESENT);
                    notificationService.notify(NotificationType.ARRIVAL, event, new StudentRecipient(student));
                }
            }
        }
        for (Teacher teacher : expected.teachers()) {
            Optional<Attendance> existing = attendanceRepository
                    .findByTeacherIdAndCalendarEventIdAndDate(teacher.getId(), event.getId(), today);
            if (existing.isEmpty()) {
                Instant joinTime = resolveJoinTime(teacher.getGoogleUserId(), teacher.getMeetDisplayName(),
                        teacher.getName(), joinTimeByUserId, joinTimeByDisplayName);
                if (joinTime == null) {
                    recordTeacherAttendance(teacher, event, AttendanceStatus.ABSENT);
                    notificationService.notify(NotificationType.TEACHER_ABSENT, event, new TeacherRecipient(teacher));
                } else if (joinTime.isAfter(classStart.plusSeconds(lateBufferMinutes * 60L))) {
                    recordTeacherAttendance(teacher, event, AttendanceStatus.LATE);
                    notificationService.notify(NotificationType.TEACHER_LATE, event, new TeacherRecipient(teacher));
                } else {
                    recordTeacherAttendance(teacher, event, AttendanceStatus.PRESENT);
                    notificationService.notify(NotificationType.TEACHER_ARRIVED, event, new TeacherRecipient(teacher));
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
    private ResolvedParticipants resolveAndAutoLearn(List<MeetParticipant> participants) {
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

    private ExpectedParticipants getExpectedParticipants(CalendarEvent event) {
        List<Student> students = new ArrayList<>();
        List<Teacher> teachers = new ArrayList<>();
        if (event.getAttendeeEmails() == null) return new ExpectedParticipants(students, teachers);
        for (String email : event.getAttendeeEmails()) {
            studentRepository.findByMeetEmailAndActiveTrue(email).ifPresent(students::add);
            teacherRepository.findByMeetEmailAndActiveTrue(email).ifPresent(teachers::add);
        }
        return new ExpectedParticipants(students, teachers);
    }

    private void recordStudentAttendance(Student student, CalendarEvent event, AttendanceStatus status) {
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

    private void recordTeacherAttendance(Teacher teacher, CalendarEvent event, AttendanceStatus status) {
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
}
