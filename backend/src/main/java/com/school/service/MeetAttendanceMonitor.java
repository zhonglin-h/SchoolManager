package com.school.service;

import com.school.entity.Attendance;
import com.school.entity.AttendanceStatus;
import com.school.entity.Student;
import com.school.integration.MeetClient;
import com.school.integration.MeetParticipant;
import com.school.model.CalendarEvent;
import com.school.repository.AttendanceRepository;
import com.school.repository.StudentRepository;
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
    private final AttendanceRepository attendanceRepository;
    private final NotificationService notificationService;
    private final MeetClient googleMeetClient;
    private final ThreadPoolTaskScheduler taskScheduler;

    @Value("${app.attendance.end-buffer-minutes}")
    private int endBufferMinutes;

    private final Map<String, ScheduledFuture<?>> pollingFutures = new ConcurrentHashMap<>();
    private final List<ScheduledCheck> upcomingChecks = new CopyOnWriteArrayList<>();

    public record ScheduledCheck(String eventId, String eventTitle, String checkType, Instant scheduledAt) {}

    public MeetAttendanceMonitor(CalendarSyncService calendarSyncService,
                                  StudentRepository studentRepository,
                                  AttendanceRepository attendanceRepository,
                                  NotificationService notificationService,
                                  MeetClient googleMeetClient,
                                  ThreadPoolTaskScheduler taskScheduler) {
        this.calendarSyncService = calendarSyncService;
        this.studentRepository = studentRepository;
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

        upcomingChecks.clear();
        Instant now = Instant.now();

        for (CalendarEvent event : events) {
            Instant minus15 = event.getStartTime().minusMinutes(15)
                    .atZone(ZoneId.systemDefault()).toInstant();
            Instant minus3 = event.getStartTime().minusMinutes(3)
                    .atZone(ZoneId.systemDefault()).toInstant();
            Instant start = event.getStartTime()
                    .atZone(ZoneId.systemDefault()).toInstant();
            Instant endWithBuffer = event.getEndTime().plusMinutes(endBufferMinutes)
                    .atZone(ZoneId.systemDefault()).toInstant();

            if (minus15.isAfter(now)) {
                upcomingChecks.add(new ScheduledCheck(event.getId(), event.getTitle(), "MEETING_NOT_STARTED_15", minus15));
                taskScheduler.schedule(() -> {
                    upcomingChecks.removeIf(c -> c.eventId().equals(event.getId()) && c.checkType().equals("MEETING_NOT_STARTED_15"));
                    checkMeetingStarted(event, "MEETING_NOT_STARTED_15");
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
            if (endWithBuffer.isAfter(now)) {
                upcomingChecks.add(new ScheduledCheck(event.getId(), event.getTitle(), "SESSION_FINALIZE", endWithBuffer));
                taskScheduler.schedule(() -> {
                    upcomingChecks.removeIf(c -> c.eventId().equals(event.getId()) && c.checkType().equals("SESSION_FINALIZE"));
                    finalizeSession(event);
                }, endWithBuffer);
            }
        }
    }

    public void checkMeetingStarted(CalendarEvent event, String type) {
        try {
            boolean active = googleMeetClient.isMeetingActive(event.getSpaceCode());
            if (!active) {
                notificationService.notifyMeetingNotStarted(event, type);
            }
        } catch (Exception e) {
            log.warn("Failed to check meeting started for {}: {}", event.getId(), e.getMessage());
        }
    }

    public void checkPreClassJoins(CalendarEvent event) {
        try {
            List<MeetParticipant> participants = googleMeetClient.getActiveParticipants(event.getSpaceCode());
            List<Student> expectedStudents = getExpectedStudents(event);
            Set<Long> presentIds = resolveAndAutoLearn(participants);
            for (Student student : expectedStudents) {
                if (!presentIds.contains(student.getId())) {
                    notificationService.notifyNotYetJoined(event, student);
                }
            }
        } catch (Exception e) {
            log.warn("Failed pre-class join check for {}: {}", event.getId(), e.getMessage());
        }
    }

    public void startSessionPolling(CalendarEvent event) {
        List<Student> expectedStudents = getExpectedStudents(event);
        Set<Long> seenStudentIds = new HashSet<>();

        try {
            List<MeetParticipant> participants = googleMeetClient.getActiveParticipants(event.getSpaceCode());
            Set<Long> presentIds = resolveAndAutoLearn(participants);
            for (Student student : expectedStudents) {
                if (presentIds.contains(student.getId())) {
                    seenStudentIds.add(student.getId());
                    recordAttendance(student, event, AttendanceStatus.PRESENT);
                }
            }
        } catch (Exception e) {
            log.warn("Failed session start poll for {}: {}", event.getId(), e.getMessage());
        }

        ScheduledFuture<?>[] futureHolder = new ScheduledFuture<?>[1];
        futureHolder[0] = taskScheduler.scheduleAtFixedRate(() -> {
            try {
                List<MeetParticipant> participants = googleMeetClient.getActiveParticipants(event.getSpaceCode());
                Set<Long> presentIds = resolveAndAutoLearn(participants);
                for (Student student : expectedStudents) {
                    if (!seenStudentIds.contains(student.getId()) && presentIds.contains(student.getId())) {
                        seenStudentIds.add(student.getId());
                        recordAttendance(student, event, AttendanceStatus.LATE);
                        notificationService.notifyArrival(event, student);
                        notificationService.notifyLate(event, student);
                    }
                }
                if (seenStudentIds.size() >= expectedStudents.size() && !expectedStudents.isEmpty()) {
                    notificationService.notifyAllPresent(event);
                    ScheduledFuture<?> future = pollingFutures.remove(event.getId());
                    if (future != null) future.cancel(false);
                }
            } catch (Exception e) {
                log.warn("Failed polling for {}: {}", event.getId(), e.getMessage());
            }
        }, java.time.Duration.ofSeconds(60));

        pollingFutures.put(event.getId(), futureHolder[0]);
    }

    public void finalizeSession(CalendarEvent event) {
        ScheduledFuture<?> future = pollingFutures.remove(event.getId());
        if (future != null) future.cancel(false);

        List<Student> expectedStudents = getExpectedStudents(event);
        LocalDate today = LocalDate.now();

        for (Student student : expectedStudents) {
            Optional<Attendance> existing = attendanceRepository
                    .findByStudentIdAndCalendarEventIdAndDate(student.getId(), event.getId(), today);
            if (existing.isEmpty()) {
                recordAttendance(student, event, AttendanceStatus.ABSENT);
                notificationService.notifyAbsent(event, student);
            }
        }
    }

    /**
     * Resolves a list of Meet participants to student IDs.
     * Matching priority: googleUserId → displayName (case-insensitive).
     * When a match is made via displayName and the student has no googleUserId yet, it is saved automatically.
     *
     * @return set of matched student IDs
     */
    private Set<Long> resolveAndAutoLearn(List<MeetParticipant> participants) {
        Set<Long> matched = new HashSet<>();
        for (MeetParticipant participant : participants) {
            Optional<Student> student = Optional.empty();

            if (participant.googleUserId() != null) {
                student = studentRepository.findByGoogleUserId(participant.googleUserId());
            }

            if (student.isEmpty() && participant.displayName() != null) {
                student = studentRepository.findByNameIgnoreCase(participant.displayName());
                // Auto-learn: persist the googleUserId so future lookups skip the name match
                if (student.isPresent() && participant.googleUserId() != null
                        && student.get().getGoogleUserId() == null) {
                    student.get().setGoogleUserId(participant.googleUserId());
                    studentRepository.save(student.get());
                    log.info("Linked googleUserId {} to student '{}'",
                            participant.googleUserId(), student.get().getName());
                }
            }

            student.ifPresent(s -> matched.add(s.getId()));
        }
        return matched;
    }

    private List<Student> getExpectedStudents(CalendarEvent event) {
        List<Student> students = new ArrayList<>();
        if (event.getAttendeeEmails() == null) return students;
        for (String email : event.getAttendeeEmails()) {
            studentRepository.findByMeetEmail(email).ifPresent(students::add);
        }
        return students;
    }

    private void recordAttendance(Student student, CalendarEvent event, AttendanceStatus status) {
        LocalDate today = LocalDate.now();
        Optional<Attendance> existing = attendanceRepository
                .findByStudentIdAndCalendarEventIdAndDate(student.getId(), event.getId(), today);
        if (existing.isEmpty()) {
            Attendance attendance = Attendance.builder()
                    .student(student)
                    .calendarEventId(event.getId())
                    .date(today)
                    .status(status)
                    .build();
            attendanceRepository.save(attendance);
        }
    }
}
