package com.school.service.meet;

import com.school.entity.Attendance;
import com.school.entity.AttendanceStatus;
import com.school.entity.Person;
import com.school.entity.PersonType;
import com.school.integration.MeetClient;
import com.school.integration.MeetParticipant;
import com.school.model.CalendarEvent;
import com.school.repository.AttendanceRepository;
import com.school.service.CheckpointSubject;
import com.school.service.NotificationService;
import com.school.service.NotificationType;
import com.school.service.PollingDeltaSubject;
import com.school.service.UpcomingChecksRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

@Slf4j
@Service
class MeetSessionPollingService {

    private final MeetParticipantResolver participantResolver;
    private final NotificationService notificationService;
    private final MeetClient googleMeetClient;
    private final ThreadPoolTaskScheduler taskScheduler;
    private final AttendanceRepository attendanceRepository;
    private final UpcomingChecksRegistry upcomingChecksRegistry;

    @Value("${app.attendance.late-buffer-minutes}")
    private int lateBufferMinutes;

    private final Map<String, ScheduledFuture<?>> pollingFutures = new ConcurrentHashMap<>();

    MeetSessionPollingService(MeetParticipantResolver participantResolver,
                              NotificationService notificationService,
                              MeetClient googleMeetClient,
                              ThreadPoolTaskScheduler taskScheduler,
                              AttendanceRepository attendanceRepository,
                              UpcomingChecksRegistry upcomingChecksRegistry) {
        this.participantResolver = participantResolver;
        this.notificationService = notificationService;
        this.googleMeetClient = googleMeetClient;
        this.taskScheduler = taskScheduler;
        this.attendanceRepository = attendanceRepository;
        this.upcomingChecksRegistry = upcomingChecksRegistry;
    }

    /** Stops and removes any active polling loop for the given event, and clears its registry entry. */
    void cancelPollingFor(String eventId) {
        ScheduledFuture<?> f = pollingFutures.remove(eventId);
        if (f != null) {
            f.cancel(false);
        }
        upcomingChecksRegistry.removePollingEntry(eventId);
    }

    /**
     * Checks whether the Meet room is open; sends a {@code type} notification if it is not.
     * Used for both the T-15 and any repeat reminders while the meeting hasn't started.
     */
    void checkMeetingStarted(CalendarEvent event, NotificationType type) {
        if (!isMeetingActive(event)) {
            notificationService.notify(type, event, null);
        }
    }

    /**
     * Returns whether the Meet room is currently active.
     * On API failures, returns false and logs a warning.
     */
    boolean isMeetingActive(CalendarEvent event) {
        try {
            return googleMeetClient.isMeetingActive(event.getSpaceCode());
        } catch (Exception e) {
            log.warn("Failed to check meeting started for {}: {}", event.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * At T-2 and T+5 min, sends one consolidated attendance checkpoint using DB records
     * (so someone who joined then left is correctly shown as arrived), including unmatched guests.
     */
    void checkNotYetJoined(CalendarEvent event, String checkLabel) {
        try {
            LocalDate today = LocalDate.now();
            List<MeetParticipant> participants = googleMeetClient.getActiveParticipants(event.getSpaceCode());
            participantResolver.resolveAndAutoLearn(participants);
            ExpectedParticipants expected = participantResolver.getExpectedParticipants(event);
            List<String> arrivedNames = new ArrayList<>();
            List<String> notArrivedNames = new ArrayList<>();
            forEachExpectedPerson(expected, (person, personType) -> {
                if (attendanceRepository.findByPersonIdAndCalendarEventIdAndDate(
                        person.getId(), event.getId(), today).isPresent()) {
                    arrivedNames.add(person.getName());
                } else {
                    notArrivedNames.add(person.getName());
                }
            });
            notificationService.notify(NotificationType.ATTENDANCE_CHECKPOINT, event,
                    new CheckpointSubject(checkLabel, arrivedNames, notArrivedNames,
                            participantResolver.findUnmatchedInvitees(event),
                            participantResolver.findUnmatchedParticipants(participants, expected)));
        } catch (Exception e) {
            log.warn("Failed attendance checkpoint {} for {}: {}", checkLabel, event.getId(), e.getMessage());
        }
    }

    /**
     * Begins the 60-second attendance polling loop at class start time.
     * Takes an immediate snapshot, then polls every minute until all expected participants
     * have been seen.
     * If the meeting room isn't open yet, keeps sending reminders on each tick until it is.
     */
    void startSessionPolling(CalendarEvent event) {
        beginSessionPolling(event, false, "Failed session start poll for {}: {}");
    }

    /**
     * Called on startup when a session is already in progress (start <= now < end).
     * Pre-seeds seen-sets from existing DB attendance records so we never double-notify,
     * then immediately takes a participant snapshot and starts the 60-second polling loop.
     * If the meeting room isn't open yet, keeps sending reminders on each tick until it is.
     */
    void resumeSessionPolling(CalendarEvent event) {
        beginSessionPolling(event, true, "Failed catch-up snapshot for {}: {}");
    }

    private void beginSessionPolling(CalendarEvent event, boolean preSeedExistingAttendance, String snapshotFailureLogPattern) {
        PollingContext context = createPollingContext(event);
        if (preSeedExistingAttendance) {
            preSeedSeenAttendance(event, context.seenStudentIds(), context.seenTeacherIds());
        }
        int totalExpected = getTotalExpectedParticipants(event);
        runInitialSnapshot(event, context, snapshotFailureLogPattern);
        if (completeIfAllPresent(event, context, totalExpected, false)) {
            return;
        }
        schedulePollingLoop(event, context);
    }

    private PollingContext createPollingContext(CalendarEvent event) {
        Set<Long> seenStudentIds = new HashSet<>();
        Set<Long> seenTeacherIds = new HashSet<>();
        Set<Long> missingExpectedIds = new HashSet<>();
        Set<String> seenKnownButUnexpectedKeys = new HashSet<>();
        Set<String> seenNotInSystemKeys = new HashSet<>();
        Instant lateThreshold = calculateLateThreshold(event);
        AtomicBoolean meetingActive = new AtomicBoolean(false);
        return new PollingContext(
                seenStudentIds, seenTeacherIds, missingExpectedIds,
                seenKnownButUnexpectedKeys, seenNotInSystemKeys,
                lateThreshold, meetingActive);
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
                PollingChangeSet changeSet = processParticipantsForPollingDelta(event, activeParticipants, context);
                emitPollingDeltaIfChanged(event, changeSet);
            } else {
                sendMeetingStartReminder(event);
            }
        } catch (Exception e) {
            log.warn(failureLogPattern, event.getId(), e.getMessage());
        }
    }

    /**
     * Schedules the 60-second polling loop shared by both start and resume paths.
     * Each tick refreshes {@code meetingActive} from Meet. If the room is not active, the tick
     * sends a reminder and skips participant processing until the room goes live again.
     */
    private void schedulePollingLoop(CalendarEvent event, PollingContext context) {
        int totalExpected = getTotalExpectedParticipants(event);
        AtomicReference<ScheduledFuture<?>> futureRef = new AtomicReference<>();

        futureRef.set(taskScheduler.scheduleAtFixedRate(() -> {
            try {
                boolean meetingActiveNow;
                try {
                    meetingActiveNow = googleMeetClient.isMeetingActive(event.getSpaceCode());
                } catch (Exception e) {
                    log.warn("Failed to check meeting active for {}: {}", event.getId(), e.getMessage());
                    return;
                }
                context.meetingActive().set(meetingActiveNow);
                if (!meetingActiveNow) {
                    sendMeetingStartReminder(event);
                    return;
                }

                List<MeetParticipant> activeParticipants = googleMeetClient.getActiveParticipants(event.getSpaceCode());
                PollingChangeSet changeSet = processParticipantsForPollingDelta(event, activeParticipants, context);
                emitPollingDeltaIfChanged(event, changeSet);

                if (completeIfAllPresent(event, context, totalExpected, true)) {
                    return;
                }
            } catch (Exception e) {
                log.warn("Failed polling for {}: {}", event.getId(), e.getMessage());
            }
        }, Duration.ofSeconds(60)));

        pollingFutures.put(event.getId(), futureRef.get());
    }

    private PollingChangeSet processParticipantsForPollingDelta(
            CalendarEvent event, List<MeetParticipant> participants, PollingContext context) {
        ResolvedParticipants resolved = participantResolver.resolveAndAutoLearn(participants);
        ExpectedParticipants expected = participantResolver.getExpectedParticipants(event);
        LocalDate today = LocalDate.now();

        List<String> arrivedNames = new ArrayList<>();
        List<String> lateArrivedNames = new ArrayList<>();
        collectExpectedArrivals(expected.students(), resolved.studentIds(), resolved.joinTimes(),
                context.seenStudentIds(), context.lateThreshold(), event, today, arrivedNames, lateArrivedNames);
        collectExpectedArrivals(expected.teachers(), resolved.teacherIds(), resolved.joinTimes(),
                context.seenTeacherIds(), context.lateThreshold(), event, today, arrivedNames, lateArrivedNames);

        MissingTransitions missingTransitions = updateMissingTransitions(expected, context);

        UnknownParticipantClassification unknowns = participantResolver.classifyUnknownParticipants(participants, expected);
        UnknownTransitions unknownTransitions = updateUnknownTransitions(unknowns, context);

        return new PollingChangeSet(
                arrivedNames,
                lateArrivedNames,
                missingTransitions.newlyMissingNames(),
                missingTransitions.noLongerMissingNames(),
                unknownTransitions.knownButUnexpectedNames(),
                unknownTransitions.notInSystemNames());
    }

    private void collectExpectedArrivals(List<Person> expectedPeople,
                                         Set<Long> resolvedIds,
                                         Map<Long, Instant> joinTimes,
                                         Set<Long> seenIds,
                                         Instant lateThreshold,
                                         CalendarEvent event,
                                         LocalDate today,
                                         List<String> arrivedNames,
                                         List<String> lateArrivedNames) {
        for (Person person : expectedPeople) {
            if (!seenIds.contains(person.getId()) && resolvedIds.contains(person.getId())) {
                seenIds.add(person.getId());
                if (attendanceRepository
                        .findByPersonIdAndCalendarEventIdAndDate(person.getId(), event.getId(), today).isPresent()) {
                    continue;
                }
                Instant joinTime = joinTimes.getOrDefault(person.getId(), Instant.now());
                AttendanceStatus status = joinTime.isAfter(lateThreshold) ? AttendanceStatus.LATE : AttendanceStatus.PRESENT;
                recordAttendance(person, event, status, today);
                if (status == AttendanceStatus.LATE) {
                    lateArrivedNames.add(person.getName());
                } else {
                    arrivedNames.add(person.getName());
                }
            }
        }
    }

    private MissingTransitions updateMissingTransitions(ExpectedParticipants expected, PollingContext context) {
        Map<Long, String> expectedNamesById = new HashMap<>();
        Set<Long> currentMissingExpectedIds = new HashSet<>();
        for (Person student : expected.students()) {
            expectedNamesById.put(student.getId(), student.getName());
            if (!context.seenStudentIds().contains(student.getId())) {
                currentMissingExpectedIds.add(student.getId());
            }
        }
        for (Person teacher : expected.teachers()) {
            expectedNamesById.put(teacher.getId(), teacher.getName());
            if (!context.seenTeacherIds().contains(teacher.getId())) {
                currentMissingExpectedIds.add(teacher.getId());
            }
        }

        List<String> newlyMissingNames = new ArrayList<>();
        for (Long id : currentMissingExpectedIds) {
            if (!context.missingExpectedIds().contains(id)) {
                String name = expectedNamesById.get(id);
                if (name != null) {
                    newlyMissingNames.add(name);
                }
            }
        }

        List<String> noLongerMissingNames = new ArrayList<>();
        for (Long id : context.missingExpectedIds()) {
            if (!currentMissingExpectedIds.contains(id)) {
                String name = expectedNamesById.get(id);
                if (name != null) {
                    noLongerMissingNames.add(name);
                }
            }
        }

        context.missingExpectedIds().clear();
        context.missingExpectedIds().addAll(currentMissingExpectedIds);
        return new MissingTransitions(newlyMissingNames, noLongerMissingNames);
    }

    private UnknownTransitions updateUnknownTransitions(UnknownParticipantClassification unknowns, PollingContext context) {
        List<String> knownButUnexpectedNames = new ArrayList<>();
        for (UnknownParticipantEntry entry : unknowns.knownButUnexpected()) {
            if (context.seenKnownButUnexpectedKeys().add(entry.key())) {
                knownButUnexpectedNames.add(entry.displayName());
            }
        }

        List<String> notInSystemNames = new ArrayList<>();
        for (UnknownParticipantEntry entry : unknowns.notInSystem()) {
            if (context.seenNotInSystemKeys().add(entry.key())) {
                notInSystemNames.add(entry.displayName());
            }
        }
        return new UnknownTransitions(knownButUnexpectedNames, notInSystemNames);
    }

    private void emitPollingDeltaIfChanged(CalendarEvent event, PollingChangeSet changeSet) {
        if (!changeSet.hasChanges()) {
            return;
        }
        notificationService.notify(NotificationType.POLLING_DELTA, event,
                new PollingDeltaSubject(
                        changeSet.arrivedNames(),
                        changeSet.lateArrivedNames(),
                        changeSet.newlyMissingNames(),
                        changeSet.noLongerMissingNames(),
                        changeSet.knownButUnexpectedNames(),
                        changeSet.notInSystemNames()));
    }

    private boolean completeIfAllPresent(CalendarEvent event, PollingContext context,
                                         int totalExpected, boolean cancelPollingFuture) {
        if (totalExpected <= 0
                || !hasSeenAllExpectedParticipants(context.seenStudentIds(), context.seenTeacherIds(), totalExpected)) {
            return false;
        }
        notificationService.notify(NotificationType.ALL_PRESENT, event, null);
        if (cancelPollingFuture) {
            cancelPollingFor(event.getId());
        } else {
            upcomingChecksRegistry.removePollingEntry(event.getId());
        }
        log.info("All participants already present for {}; skipping polling", event.getId());
        return true;
    }

    private boolean hasSeenAllExpectedParticipants(Set<Long> seenStudentIds, Set<Long> seenTeacherIds, int totalExpected) {
        return seenStudentIds.size() + seenTeacherIds.size() >= totalExpected;
    }

    /**
     * Returns the number of expected participants from calendar attendees.
     * Applies the same rule everywhere this count is used.
     */
    private int getTotalExpectedParticipants(CalendarEvent event) {
        int attendeeCount = event.getAttendeeEmails() != null ? event.getAttendeeEmails().size() : 0;
        return Math.max(0, attendeeCount);
    }

    /** Fires a MEETING_NOT_STARTED_15 notification without a specific recipient (broadcast). */
    private void sendMeetingStartReminder(CalendarEvent event) {
        notificationService.notify(NotificationType.MEETING_NOT_STARTED_15, event, null);
    }

    private Instant calculateLateThreshold(CalendarEvent event) {
        Instant classStart = event.getStartTime().atZone(ZoneId.systemDefault()).toInstant();
        return classStart.plusSeconds(lateBufferMinutes * 60L);
    }

    private void recordAttendance(Person person, CalendarEvent event, AttendanceStatus status, LocalDate today) {
        attendanceRepository.save(Attendance.builder()
                .person(person)
                .calendarEventId(event.getId())
                .eventTitle(event.getTitle())
                .date(today)
                .status(status)
                .build());
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
            Set<Long> missingExpectedIds,
            Set<String> seenKnownButUnexpectedKeys,
            Set<String> seenNotInSystemKeys,
            Instant lateThreshold,
            AtomicBoolean meetingActive
    ) {}

    private record PollingChangeSet(
            List<String> arrivedNames,
            List<String> lateArrivedNames,
            List<String> newlyMissingNames,
            List<String> noLongerMissingNames,
            List<String> knownButUnexpectedNames,
            List<String> notInSystemNames
    ) {
        boolean hasChanges() {
            return !arrivedNames.isEmpty()
                    || !lateArrivedNames.isEmpty()
                    || !newlyMissingNames.isEmpty()
                    || !noLongerMissingNames.isEmpty()
                    || !knownButUnexpectedNames.isEmpty()
                    || !notInSystemNames.isEmpty();
        }
    }

    private record MissingTransitions(
            List<String> newlyMissingNames,
            List<String> noLongerMissingNames
    ) {}

    private record UnknownTransitions(
            List<String> knownButUnexpectedNames,
            List<String> notInSystemNames
    ) {}
}
