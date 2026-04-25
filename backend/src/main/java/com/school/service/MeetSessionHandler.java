package com.school.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import com.school.entity.Attendance;
import com.school.entity.AttendanceStatus;
import com.school.entity.Student;
import com.school.entity.Teacher;
import com.school.integration.MeetClient;
import com.school.integration.MeetParticipant;
import com.school.model.CalendarEvent;
import com.school.repository.AttendanceRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Executes the four per-event monitoring tasks: pre-meeting checks, live arrival polling,
 * and end-of-session attendance finalization. Owns the polling future lifecycle.
 */
@Slf4j
@Service
public class MeetSessionHandler {

    private final MeetAttendanceHelper attendanceHelper;
    private final NotificationService notificationService;
    private final MeetClient googleMeetClient;
    private final ThreadPoolTaskScheduler taskScheduler;
    private final AttendanceRepository attendanceRepository;
    private final UpcomingChecksRegistry upcomingChecksRegistry;

    @Value("${app.attendance.late-buffer-minutes}")
    private int lateBufferMinutes;

    private final Map<String, ScheduledFuture<?>> pollingFutures = new ConcurrentHashMap<>();

    public MeetSessionHandler(MeetAttendanceHelper attendanceHelper,
                               NotificationService notificationService,
                               MeetClient googleMeetClient,
                               ThreadPoolTaskScheduler taskScheduler,
                               AttendanceRepository attendanceRepository,
                               UpcomingChecksRegistry upcomingChecksRegistry) {
        this.attendanceHelper = attendanceHelper;
        this.notificationService = notificationService;
        this.googleMeetClient = googleMeetClient;
        this.taskScheduler = taskScheduler;
        this.attendanceRepository = attendanceRepository;
        this.upcomingChecksRegistry = upcomingChecksRegistry;
    }

    /** Stops and removes any active polling loop for the given event, and clears its registry entry. */
    void cancelPollingFor(String eventId) {
        ScheduledFuture<?> f = pollingFutures.remove(eventId);
        if (f != null) f.cancel(false);
        upcomingChecksRegistry.removePollingEntry(eventId);
    }

    /**
     * Checks whether the Meet room is open; sends a {@code type} notification if it is not.
     * Used for both the T−15 and any repeat reminders while the meeting hasn't started.
     */
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

    /**
     * At T−3 min, notifies every expected participant (student or teacher) who has not yet joined.
     * Also sends an unmatched-guests notification for any invitee email that is not in the DB.
     * Uses the live participant list, so anyone already in the room is silently skipped.
     */
    public void checkPreClassJoins(CalendarEvent event) {
        try {
            List<MeetParticipant> participants = googleMeetClient.getActiveParticipants(event.getSpaceCode());
            ExpectedParticipants expected = attendanceHelper.getExpectedParticipants(event);
            ResolvedParticipants resolved = attendanceHelper.resolveAndAutoLearn(participants);
            for (Student student : expected.students()) {
                if (!resolved.studentIds().contains(student.getId())) {
                    notificationService.notify(NotificationType.NOT_YET_JOINED, event, new StudentRecipient(student));
                }
            }
            for (Teacher teacher : expected.teachers()) {
                if (!resolved.teacherIds().contains(teacher.getId())) {
                    notificationService.notify(NotificationType.NOT_YET_JOINED, event, new TeacherRecipient(teacher));
                }
            }
            List<String> unmatchedInvitees = attendanceHelper.findUnmatchedInvitees(event);
            if (!unmatchedInvitees.isEmpty()) {
                notificationService.notify(
                        NotificationType.UNMATCHED_GUESTS,
                        event,
                        new GuestRecipient(unmatchedInvitees));
            }
        } catch (Exception e) {
            log.warn("Failed pre-class join check for {}: {}", event.getId(), e.getMessage());
        }
    }

    /**
     * Begins the 60-second attendance polling loop at class start time.
     * Takes an immediate snapshot, then polls every minute until all expected participants
     * have been seen or {@link #finalizeSession} cancels the future.
     * If the meeting room isn't open yet, keeps sending reminders on each tick until it is.
     */
    public void startSessionPolling(CalendarEvent event) {
        ExpectedParticipants expected = attendanceHelper.getExpectedParticipants(event);
        Set<Long> seenStudentIds = new HashSet<>();
        Set<Long> seenTeacherIds = new HashSet<>();
        Instant lateThreshold = event.getStartTime().atZone(ZoneId.systemDefault()).toInstant()
                .plusSeconds(lateBufferMinutes * 60L);
        AtomicBoolean meetingActive = new AtomicBoolean(false);

        try {
            if (googleMeetClient.isMeetingActive(event.getSpaceCode())) {
                meetingActive.set(true);
                attendanceHelper.processParticipants(event, googleMeetClient.getActiveParticipants(event.getSpaceCode()),
                        expected, seenStudentIds, seenTeacherIds, lateThreshold);
                notifyMissing(event, expected, seenStudentIds, seenTeacherIds);
            } else {
                sendMeetingStartReminder(event);
            }
        } catch (Exception e) {
            log.warn("Failed session start poll for {}: {}", event.getId(), e.getMessage());
        }

        schedulePollingLoop(event, expected, seenStudentIds, seenTeacherIds, lateThreshold, meetingActive);
    }

    /**
     * Called on startup when a session is already in progress (start &le; now &lt; end).
     * Pre-seeds seen-sets from existing DB attendance records so we never double-notify,
     * then immediately takes a participant snapshot and starts the 60-second polling loop.
     * If the meeting room isn't open yet, keeps sending reminders on each tick until it is.
     */
    public void resumeSessionPolling(CalendarEvent event) {
        ExpectedParticipants expected = attendanceHelper.getExpectedParticipants(event);
        int totalExpected = event.getAttendeeEmails().size();
        Set<Long> seenStudentIds = new HashSet<>();
        Set<Long> seenTeacherIds = new HashSet<>();

        // Pre-seed from attendance already recorded before the restart
        attendanceRepository.findByCalendarEventIdAndDate(event.getId(), LocalDate.now())
                .forEach(a -> {
                    log.debug("Pre-seeding attendance for {}: studentId={}, teacherId={}, status={}",
                            event.getId(), a.getStudent() != null ? a.getStudent().getId() : null,
                            a.getTeacher() != null ? a.getTeacher().getId() : null, a.getStatus());
                    if (a.getStudent() != null) seenStudentIds.add(a.getStudent().getId());
                    if (a.getTeacher() != null) seenTeacherIds.add(a.getTeacher().getId());
                });

        Instant lateThreshold = event.getStartTime().atZone(ZoneId.systemDefault()).toInstant()
                .plusSeconds(lateBufferMinutes * 60L);
        AtomicBoolean meetingActive = new AtomicBoolean(false);

        try {
            if (googleMeetClient.isMeetingActive(event.getSpaceCode())) {
                meetingActive.set(true);
                attendanceHelper.processParticipants(event, googleMeetClient.getActiveParticipants(event.getSpaceCode()),
                        expected, seenStudentIds, seenTeacherIds, lateThreshold);
            } else {
                sendMeetingStartReminder(event);
            }
        } catch (Exception e) {
            log.warn("Failed catch-up snapshot for {}: {}", event.getId(), e.getMessage());
        }

        if (seenStudentIds.size() + seenTeacherIds.size() >= totalExpected) {
            log.info("All participants already present for {}; skipping polling", event.getId());
            upcomingChecksRegistry.removePollingEntry(event.getId());
            return;
        }

        notifyMissing(event, expected, seenStudentIds, seenTeacherIds);
        
        schedulePollingLoop(event, expected, seenStudentIds, seenTeacherIds, lateThreshold, meetingActive);
    }

    /**
     * Schedules the 60-second polling loop shared by both start and resume paths.
     * If the meeting room isn't open yet ({@code meetingActive} is false), each tick sends a
     * reminder and skips participant processing until the room goes live.
     */
    private void schedulePollingLoop(CalendarEvent event, ExpectedParticipants expected,
            Set<Long> seenStudentIds, Set<Long> seenTeacherIds,
            Instant lateThreshold, AtomicBoolean meetingActive) {
        int totalExpected = event.getAttendeeEmails().size();
        AtomicReference<ScheduledFuture<?>> futureRef = new AtomicReference<>();

        futureRef.set(taskScheduler.scheduleAtFixedRate(() -> {
            try {
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

                attendanceHelper.processParticipants(event, googleMeetClient.getActiveParticipants(event.getSpaceCode()),
                        expected, seenStudentIds, seenTeacherIds, lateThreshold);
                notifyMissing(event, expected, seenStudentIds, seenTeacherIds);

                List<String> unmatchedInvitees = attendanceHelper.findUnmatchedInvitees(event);
                if (!unmatchedInvitees.isEmpty()) {
                    notificationService.notify(
                            NotificationType.UNMATCHED_GUESTS,
                            event,
                            new GuestRecipient(unmatchedInvitees));
                }

                if (seenStudentIds.size() + seenTeacherIds.size() >= totalExpected && totalExpected > 0) {
                    notificationService.notify(NotificationType.ALL_PRESENT, event, null);
                    cancelPollingFor(event.getId());
                }
            } catch (Exception e) {
                log.warn("Failed polling for {}: {}", event.getId(), e.getMessage());
            }
        }, Duration.ofSeconds(60)));

        pollingFutures.put(event.getId(), futureRef.get());
    }

    private void notifyMissing(CalendarEvent event, ExpectedParticipants expected,
                               Set<Long> seenStudentIds, Set<Long> seenTeacherIds) {
        for (Student student : expected.students()) {
            if (!seenStudentIds.contains(student.getId())) {
                notificationService.notify(NotificationType.NOT_YET_JOINED, event, new StudentRecipient(student));
            }
        }
        for (Teacher teacher : expected.teachers()) {
            if (!seenTeacherIds.contains(teacher.getId())) {
                notificationService.notify(NotificationType.NOT_YET_JOINED, event, new TeacherRecipient(teacher));
            }
        }
    }

    /** Fires a MEETING_NOT_STARTED_15 notification without a specific recipient (broadcast). */
    private void sendMeetingStartReminder(CalendarEvent event) {
        notificationService.notify(NotificationType.MEETING_NOT_STARTED_15, event, null);
    }

    /**
     * Stops live polling and reconciles attendance for everyone not already recorded.
     * Fetches the full participant history (including people who left mid-session) and
     * uses join times to determine PRESENT / LATE / ABSENT for any remaining gaps.
     */
    public void finalizeSession(CalendarEvent event) {
        cancelPollingFor(event.getId());

        Instant classStart = event.getStartTime().atZone(ZoneId.systemDefault()).toInstant();
        LocalDate today = LocalDate.now();

        // Build a join-time map from the full participant history (including those who left)
        Map<String, Instant> joinTimeByUserId = new HashMap<>();
        Map<String, Instant> joinTimeByDisplayName = new HashMap<>();
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

        ExpectedParticipants expected = attendanceHelper.getExpectedParticipants(event);

        for (Student student : expected.students()) {
            Optional<Attendance> existing = attendanceRepository
                    .findByStudentIdAndCalendarEventIdAndDate(student.getId(), event.getId(), today);
            if (existing.isEmpty()) {
                Instant joinTime = attendanceHelper.resolveJoinTime(student.getGoogleUserId(), student.getMeetDisplayName(),
                        student.getName(), joinTimeByUserId, joinTimeByDisplayName);
                if (joinTime == null) {
                    attendanceHelper.recordStudentAttendance(student, event, AttendanceStatus.ABSENT);
                    notificationService.notify(NotificationType.ABSENT, event, new StudentRecipient(student));
                } else if (joinTime.isAfter(classStart.plusSeconds(lateBufferMinutes * 60L))) {
                    attendanceHelper.recordStudentAttendance(student, event, AttendanceStatus.LATE);
                    notificationService.notify(NotificationType.LATE, event, new StudentRecipient(student));
                } else {
                    attendanceHelper.recordStudentAttendance(student, event, AttendanceStatus.PRESENT);
                    notificationService.notify(NotificationType.ARRIVAL, event, new StudentRecipient(student));
                }
            }
        }
        for (Teacher teacher : expected.teachers()) {
            Optional<Attendance> existing = attendanceRepository
                    .findByTeacherIdAndCalendarEventIdAndDate(teacher.getId(), event.getId(), today);
            if (existing.isEmpty()) {
                Instant joinTime = attendanceHelper.resolveJoinTime(teacher.getGoogleUserId(), teacher.getMeetDisplayName(),
                        teacher.getName(), joinTimeByUserId, joinTimeByDisplayName);
                if (joinTime == null) {
                    attendanceHelper.recordTeacherAttendance(teacher, event, AttendanceStatus.ABSENT);
                    notificationService.notify(NotificationType.TEACHER_ABSENT, event, new TeacherRecipient(teacher));
                } else if (joinTime.isAfter(classStart.plusSeconds(lateBufferMinutes * 60L))) {
                    attendanceHelper.recordTeacherAttendance(teacher, event, AttendanceStatus.LATE);
                    notificationService.notify(NotificationType.TEACHER_LATE, event, new TeacherRecipient(teacher));
                } else {
                    attendanceHelper.recordTeacherAttendance(teacher, event, AttendanceStatus.PRESENT);
                    notificationService.notify(NotificationType.TEACHER_ARRIVED, event, new TeacherRecipient(teacher));
                }
            }
        }
    }
}
