package com.school.service;

import com.school.entity.Attendance;
import com.school.entity.AttendanceStatus;
import com.school.entity.Student;
import com.school.integration.MeetClient;
import com.school.model.CalendarEvent;
import com.school.repository.AttendanceRepository;
import com.school.repository.StudentRepository;
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
import java.util.concurrent.ScheduledFuture;

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
                taskScheduler.schedule(
                        () -> checkMeetingStarted(event, "MEETING_NOT_STARTED_15"),
                        minus15
                );
            }
            if (minus3.isAfter(now)) {
                taskScheduler.schedule(
                        () -> checkPreClassJoins(event),
                        minus3
                );
            }
            if (start.isAfter(now)) {
                taskScheduler.schedule(
                        () -> startSessionPolling(event),
                        start
                );
            }
            if (endWithBuffer.isAfter(now)) {
                taskScheduler.schedule(
                        () -> finalizeSession(event),
                        endWithBuffer
                );
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
        }
    }

    public void checkPreClassJoins(CalendarEvent event) {
        try {
            List<String> activeEmails = googleMeetClient.getActiveParticipantEmails(event.getSpaceCode());
            List<Student> expectedStudents = getExpectedStudents(event);
            for (Student student : expectedStudents) {
                if (!activeEmails.contains(student.getMeetEmail())) {
                    notificationService.notifyNotYetJoined(event, student);
                }
            }
        } catch (Exception e) {
        }
    }

    public void startSessionPolling(CalendarEvent event) {
        List<Student> expectedStudents = getExpectedStudents(event);
        Set<Long> seenStudentIds = new HashSet<>();

        try {
            List<String> activeEmails = googleMeetClient.getActiveParticipantEmails(event.getSpaceCode());
            for (Student student : expectedStudents) {
                if (activeEmails.contains(student.getMeetEmail())) {
                    seenStudentIds.add(student.getId());
                    recordAttendance(student, event, AttendanceStatus.PRESENT);
                }
            }
        } catch (Exception e) {
        }

        ScheduledFuture<?>[] futureHolder = new ScheduledFuture<?>[1];
        futureHolder[0] = taskScheduler.scheduleAtFixedRate(() -> {
            try {
                List<String> activeEmails = googleMeetClient.getActiveParticipantEmails(event.getSpaceCode());
                for (Student student : expectedStudents) {
                    if (!seenStudentIds.contains(student.getId())
                            && activeEmails.contains(student.getMeetEmail())) {
                        seenStudentIds.add(student.getId());
                        recordAttendance(student, event, AttendanceStatus.LATE);
                        notificationService.notifyArrival(event, student);
                        notificationService.notifyLate(event, student);
                    }
                }
                if (seenStudentIds.size() >= expectedStudents.size() && !expectedStudents.isEmpty()) {
                    notificationService.notifyAllPresent(event);
                    ScheduledFuture<?> future = pollingFutures.remove(event.getId());
                    if (future != null) {
                        future.cancel(false);
                    }
                }
            } catch (Exception e) {
            }
        }, java.time.Duration.ofSeconds(60));

        pollingFutures.put(event.getId(), futureHolder[0]);
    }

    public void finalizeSession(CalendarEvent event) {
        ScheduledFuture<?> future = pollingFutures.remove(event.getId());
        if (future != null) {
            future.cancel(false);
        }

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

    private List<Student> getExpectedStudents(CalendarEvent event) {
        List<Student> students = new ArrayList<>();
        if (event.getAttendeeEmails() == null) {
            return students;
        }
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
