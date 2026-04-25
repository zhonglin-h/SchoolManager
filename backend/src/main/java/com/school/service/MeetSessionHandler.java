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
import java.util.function.BiConsumer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import com.school.entity.Attendance;
import com.school.entity.AttendanceStatus;
import com.school.entity.Person;
import com.school.entity.PersonType;
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
    private final MeetJoinService meetJoinService;

    @Value("${app.attendance.late-buffer-minutes}")
    private int lateBufferMinutes;

    private final Map<String, ScheduledFuture<?>> pollingFutures = new ConcurrentHashMap<>();

    public MeetSessionHandler(MeetAttendanceHelper attendanceHelper,
                               NotificationService notificationService,
                               MeetClient googleMeetClient,
                               ThreadPoolTaskScheduler taskScheduler,
                               AttendanceRepository attendanceRepository,
                               UpcomingChecksRegistry upcomingChecksRegistry,
                               MeetJoinService meetJoinService) {
        this.attendanceHelper = attendanceHelper;
        this.notificationService = notificationService;
        this.googleMeetClient = googleMeetClient;
        this.taskScheduler = taskScheduler;
        this.attendanceRepository = attendanceRepository;
        this.upcomingChecksRegistry = upcomingChecksRegistry;
        this.meetJoinService = meetJoinService;
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
            ResolvedParticipants resolved = attendanceHelper.resolveAndAutoLearn(participants);
            ExpectedParticipants expected = attendanceHelper.getExpectedParticipants(event);
            forEachExpectedPerson(expected, (person, personType) -> {
                Set<Long> resolvedIds = personType == PersonType.STUDENT ? resolved.studentIds() : resolved.teacherIds();
                NotificationSubject subject = new PersonSubject(person);
                if (!resolvedIds.contains(person.getId())) {
                    notificationService.notify(NotificationType.NOT_YET_JOINED, event, subject);
                }
            });
            processUnmatchedGuests(event, expected, participants);
        } catch (Exception e) {
            log.warn("Failed pre-class join check for {}: {}", event.getId(), e.getMessage());
        }
    }

    public void attemptAutoJoin(CalendarEvent event) {
        try {
            meetJoinService.attemptScheduledJoin(event);
        } catch (Exception e) {
            log.warn("Failed auto-join attempt for {}: {}", event.getId(), e.getMessage());
        }
    }

    /**
     * Begins the 60-second attendance polling loop at class start time.
     * Takes an immediate snapshot, then polls every minute until all expected participants
     * have been seen or {@link #finalizeSession} cancels the future.
     * If the meeting room isn't open yet, keeps sending reminders on each tick until it is.
     */
    public void startSessionPolling(CalendarEvent event) {
        PollingContext context = createPollingContext(event);
        int totalExpected = getTotalExpectedParticipants(event);
        runInitialSnapshot(event, context, "Failed session start poll for {}: {}");
        if (hasSeenAllExpectedParticipants(context.seenStudentIds(), context.seenTeacherIds(), totalExpected)) {
            log.info("All participants already present for {}; skipping polling", event.getId());
            upcomingChecksRegistry.removePollingEntry(event.getId());
            return;
        }
        schedulePollingLoop(event, context.seenStudentIds(), context.seenTeacherIds(),
                context.lateThreshold(), context.meetingActive());
    }

    /**
     * Called on startup when a session is already in progress (start &le; now &lt; end).
     * Pre-seeds seen-sets from existing DB attendance records so we never double-notify,
     * then immediately takes a participant snapshot and starts the 60-second polling loop.
     * If the meeting room isn't open yet, keeps sending reminders on each tick until it is.
     */
    public void resumeSessionPolling(CalendarEvent event) {
        PollingContext context = createPollingContext(event);
        preSeedSeenAttendance(event, context.seenStudentIds(), context.seenTeacherIds());

        int totalExpected = getTotalExpectedParticipants(event);
        runInitialSnapshot(event, context, "Failed catch-up snapshot for {}: {}");
        if (hasSeenAllExpectedParticipants(context.seenStudentIds(), context.seenTeacherIds(), totalExpected)) {
            log.info("All participants already present for {}; skipping polling", event.getId());
            upcomingChecksRegistry.removePollingEntry(event.getId());
            return;
        }

        schedulePollingLoop(event, context.seenStudentIds(), context.seenTeacherIds(),
                context.lateThreshold(), context.meetingActive());
    }

    /**
     * Schedules the 60-second polling loop shared by both start and resume paths.
     * If the meeting room isn't open yet ({@code meetingActive} is false), each tick sends a
     * reminder and skips participant processing until the room goes live.
     */
    private void schedulePollingLoop(CalendarEvent event,
            Set<Long> seenStudentIds, Set<Long> seenTeacherIds,
            Instant lateThreshold, AtomicBoolean meetingActive) {
        int totalExpected = getTotalExpectedParticipants(event);
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

                List<MeetParticipant> activeParticipants = googleMeetClient.getActiveParticipants(event.getSpaceCode());
                processParticipants(event, activeParticipants, seenStudentIds, seenTeacherIds, lateThreshold);

                if (hasSeenAllExpectedParticipants(seenStudentIds, seenTeacherIds, totalExpected) && totalExpected > 0) {
                    notificationService.notify(NotificationType.ALL_PRESENT, event, null);
                    cancelPollingFor(event.getId());
                }
            } catch (Exception e) {
                log.warn("Failed polling for {}: {}", event.getId(), e.getMessage());
            }
        }, Duration.ofSeconds(60)));

        pollingFutures.put(event.getId(), futureRef.get());
    }

    private PollingContext createPollingContext(CalendarEvent event) {
        Set<Long> seenStudentIds = new HashSet<>();
        Set<Long> seenTeacherIds = new HashSet<>();
        Instant lateThreshold = event.getStartTime().atZone(ZoneId.systemDefault()).toInstant()
                .plusSeconds(lateBufferMinutes * 60L);
        AtomicBoolean meetingActive = new AtomicBoolean(false);
        return new PollingContext(seenStudentIds, seenTeacherIds, lateThreshold, meetingActive);
    }

    private void preSeedSeenAttendance(CalendarEvent event, Set<Long> seenStudentIds, Set<Long> seenTeacherIds) {
        attendanceRepository.findByCalendarEventIdAndDate(event.getId(), LocalDate.now())
                .forEach(a -> {
                    Person person = a.getPerson();
                    log.debug("Pre-seeding attendance for {}: personId={}, personType={}, status={}",
                            event.getId(), person != null ? person.getId() : null,
                            person != null ? person.getPersonType() : null, a.getStatus());
                    if (person != null && person.getPersonType() == PersonType.STUDENT) {
                        seenStudentIds.add(person.getId());
                    }
                    if (person != null && person.getPersonType() == PersonType.TEACHER) {
                        seenTeacherIds.add(person.getId());
                    }
                });
    }

    private void runInitialSnapshot(CalendarEvent event, PollingContext context, String failureLogPattern) {
        try {
            if (googleMeetClient.isMeetingActive(event.getSpaceCode())) {
                context.meetingActive().set(true);
                List<MeetParticipant> activeParticipants = googleMeetClient.getActiveParticipants(event.getSpaceCode());
                processParticipants(event, activeParticipants, context.seenStudentIds(), context.seenTeacherIds(), context.lateThreshold());
            } else {
                sendMeetingStartReminder(event);
            }
        } catch (Exception e) {
            log.warn(failureLogPattern, event.getId(), e.getMessage());
        }
    }

    private void processUnmatchedGuests(CalendarEvent event, ExpectedParticipants expected,
                                       List<MeetParticipant> participants) {
        List<String> unmatchedInvitees = attendanceHelper.findUnmatchedInvitees(event);
        List<String> unmatchedParticipants = attendanceHelper.findUnmatchedParticipants(participants, expected);
        if (!unmatchedInvitees.isEmpty()) {
            notificationService.notify(
                    NotificationType.UNMATCHED_GUESTS,
                    event,
                    new GuestSubject(unmatchedInvitees, unmatchedParticipants));
        }
    }

    /**
     * Returns the number of expected participants from calendar attendees.
     * Applies the same rule everywhere this count is used.
     */
    private int getTotalExpectedParticipants(CalendarEvent event) {
        int attendeeCount = event.getAttendeeEmails() != null ? event.getAttendeeEmails().size() : 0;
        return Math.max(0, attendeeCount);
    }

    private boolean hasSeenAllExpectedParticipants(Set<Long> seenStudentIds, Set<Long> seenTeacherIds, int totalExpected) {
        return seenStudentIds.size() + seenTeacherIds.size() >= totalExpected;
    }

    private void processParticipants(CalendarEvent event, List<MeetParticipant> participants,
                                     Set<Long> seenStudentIds, Set<Long> seenTeacherIds,
                                     Instant lateThreshold) {
        ResolvedParticipants resolved = attendanceHelper.resolveAndAutoLearn(participants);
        ExpectedParticipants expected = attendanceHelper.getExpectedParticipants(event);
        boolean isLate = Instant.now().isAfter(lateThreshold);

        for (Person student : expected.students()) {
            if (!seenStudentIds.contains(student.getId()) && resolved.studentIds().contains(student.getId())) {
                seenStudentIds.add(student.getId());
                AttendanceStatus status = isLate ? AttendanceStatus.LATE : AttendanceStatus.PRESENT;
                attendanceHelper.recordAttendance(student, event, status);
                notificationService.notify(isLate ? NotificationType.LATE : NotificationType.ARRIVAL,
                        event, new PersonSubject(student));
            }
        }
        for (Person teacher : expected.teachers()) {
            if (!seenTeacherIds.contains(teacher.getId()) && resolved.teacherIds().contains(teacher.getId())) {
                seenTeacherIds.add(teacher.getId());
                AttendanceStatus status = isLate ? AttendanceStatus.LATE : AttendanceStatus.PRESENT;
                attendanceHelper.recordAttendance(teacher, event, status);
                notificationService.notify(isLate ? NotificationType.LATE : NotificationType.ARRIVAL,
                        event, new PersonSubject(teacher));
            }
        }
        notifyMissing(event, expected, seenStudentIds, seenTeacherIds);
        processUnmatchedGuests(event, expected, participants);
    }

    private void notifyMissing(CalendarEvent event, ExpectedParticipants expected,
                               Set<Long> seenStudentIds, Set<Long> seenTeacherIds) {
        forEachExpectedPerson(expected, (person, personType) -> {
            Set<Long> seenIds = personType == PersonType.STUDENT ? seenStudentIds : seenTeacherIds;
            NotificationSubject subject = new PersonSubject(person);
            if (!seenIds.contains(person.getId())) {
                notificationService.notify(NotificationType.NOT_YET_JOINED, event, subject);
            }
        });
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
        Instant lateThreshold = classStart.plusSeconds(lateBufferMinutes * 60L);
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

        forEachExpectedPerson(expected, (person, personType) -> {
            NotificationSubject subject = new PersonSubject(person);
            Optional<Attendance> existing = attendanceRepository
                    .findByPersonIdAndCalendarEventIdAndDate(person.getId(), event.getId(), today);
            if (existing.isPresent()) {
                return;
            }

            Instant joinTime = attendanceHelper.resolveJoinTime(person.getGoogleUserId(), person.getMeetDisplayName(),
                    person.getName(), joinTimeByUserId, joinTimeByDisplayName);
            if (joinTime == null) {
                attendanceHelper.recordAttendance(person, event, AttendanceStatus.ABSENT);
                notificationService.notify(NotificationType.ABSENT, event, subject);
            } else if (joinTime.isAfter(lateThreshold)) {
                attendanceHelper.recordAttendance(person, event, AttendanceStatus.LATE);
                notificationService.notify(NotificationType.LATE, event, subject);
            } else {
                attendanceHelper.recordAttendance(person, event, AttendanceStatus.PRESENT);
                notificationService.notify(NotificationType.ARRIVAL, event, subject);
            }
        });
    }

    private void forEachExpectedPerson(ExpectedParticipants expected, BiConsumer<Person, PersonType> consumer) {
        for (Person student : expected.students()) {
            consumer.accept(student, PersonType.STUDENT);
        }
        for (Person teacher : expected.teachers()) {
            consumer.accept(teacher, PersonType.TEACHER);
        }
    }

    private record PollingContext(
            Set<Long> seenStudentIds,
            Set<Long> seenTeacherIds,
            Instant lateThreshold,
            AtomicBoolean meetingActive
    ) {}
}
