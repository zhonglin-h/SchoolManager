package com.school.service.meet;

import com.school.entity.Attendance;
import com.school.entity.AttendanceStatus;
import com.school.entity.Person;
import com.school.entity.PersonType;
import com.school.integration.MeetClient;
import com.school.integration.MeetParticipant;
import com.school.model.CalendarEvent;
import com.school.repository.AttendanceRepository;
import com.school.service.NotificationService;
import com.school.service.NotificationType;
import com.school.service.SessionFinalSummarySubject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

@Slf4j
@Service
class MeetSessionFinalizer {

    private final MeetParticipantResolver participantResolver;
    private final NotificationService notificationService;
    private final MeetClient googleMeetClient;
    private final AttendanceRepository attendanceRepository;

    @Value("${app.attendance.late-buffer-minutes}")
    private int lateBufferMinutes;

    MeetSessionFinalizer(MeetParticipantResolver participantResolver,
                         NotificationService notificationService,
                         MeetClient googleMeetClient,
                         AttendanceRepository attendanceRepository) {
        this.participantResolver = participantResolver;
        this.notificationService = notificationService;
        this.googleMeetClient = googleMeetClient;
        this.attendanceRepository = attendanceRepository;
    }

    /**
     * Reconciles attendance for everyone not already recorded and emits one final summary.
     * Uses full participant history (including people who left mid-session) to determine
     * PRESENT / LATE / ABSENT for missing attendance rows.
     */
    void finalizeSession(CalendarEvent event) {
        Instant lateThreshold = calculateLateThreshold(event);
        LocalDate today = LocalDate.now();

        ParticipantHistory participantHistory = fetchParticipantHistory(event);
        ExpectedParticipants expected = participantResolver.getExpectedParticipants(event);
        FinalAttendanceNames attendanceNames = collectFinalAttendanceNames(
                event, expected, today, lateThreshold,
                participantHistory.joinTimeByUserId(), participantHistory.joinTimeByDisplayName());
        UnknownParticipantClassification unknowns =
                participantResolver.classifyUnknownParticipants(participantHistory.participants(), expected);

        sendSessionFinalSummary(event, attendanceNames, unknowns);
    }

    private ParticipantHistory fetchParticipantHistory(CalendarEvent event) {
        Map<String, Instant> joinTimeByUserId = new HashMap<>();
        Map<String, Instant> joinTimeByDisplayName = new HashMap<>();
        List<MeetParticipant> participants = List.of();
        try {
            participants = googleMeetClient.getAllParticipants(event.getSpaceCode());
            indexParticipantJoinTimes(participants, joinTimeByUserId, joinTimeByDisplayName);
        } catch (Exception e) {
            log.warn("Failed to fetch all participants for finalize {}: {}", event.getId(), e.getMessage());
        }
        return new ParticipantHistory(participants, joinTimeByUserId, joinTimeByDisplayName);
    }

    private void indexParticipantJoinTimes(List<MeetParticipant> participants,
                                           Map<String, Instant> joinTimeByUserId,
                                           Map<String, Instant> joinTimeByDisplayName) {
        for (MeetParticipant participant : participants) {
            if (participant.earliestStartTime() == null) {
                continue;
            }
            if (participant.googleUserId() != null) {
                joinTimeByUserId.put(participant.googleUserId(), participant.earliestStartTime());
            }
            if (participant.displayName() != null) {
                joinTimeByDisplayName.put(participant.displayName().toLowerCase(), participant.earliestStartTime());
            }
        }
    }

    private FinalAttendanceNames collectFinalAttendanceNames(CalendarEvent event,
                                                             ExpectedParticipants expected,
                                                             LocalDate today,
                                                             Instant lateThreshold,
                                                             Map<String, Instant> joinTimeByUserId,
                                                             Map<String, Instant> joinTimeByDisplayName) {
        List<String> presentNames = new ArrayList<>();
        List<String> lateNames = new ArrayList<>();
        List<String> absentNames = new ArrayList<>();

        forEachExpectedPerson(expected, (person, personType) -> {
            AttendanceStatus status = resolveFinalAttendanceStatus(
                    person, event, today, lateThreshold, joinTimeByUserId, joinTimeByDisplayName);
            addNameByStatus(person.getName(), status, presentNames, lateNames, absentNames);
        });

        return new FinalAttendanceNames(presentNames, lateNames, absentNames);
    }

    private AttendanceStatus resolveFinalAttendanceStatus(Person person,
                                                          CalendarEvent event,
                                                          LocalDate today,
                                                          Instant lateThreshold,
                                                          Map<String, Instant> joinTimeByUserId,
                                                          Map<String, Instant> joinTimeByDisplayName) {
        Optional<Attendance> existing = attendanceRepository
                .findByPersonIdAndCalendarEventIdAndDate(person.getId(), event.getId(), today);
        if (existing.isPresent()) {
            return existing.get().getStatus();
        }

        Instant joinTime = participantResolver.resolveJoinTime(
                person.getGoogleUserId(),
                person.getMeetDisplayName(),
                person.getName(),
                joinTimeByUserId,
                joinTimeByDisplayName);
        AttendanceStatus status = statusFromJoinTime(joinTime, lateThreshold);
        recordAttendance(person, event, status, today);
        return status;
    }

    private AttendanceStatus statusFromJoinTime(Instant joinTime, Instant lateThreshold) {
        if (joinTime == null) {
            return AttendanceStatus.ABSENT;
        }
        if (joinTime.isAfter(lateThreshold)) {
            return AttendanceStatus.LATE;
        }
        return AttendanceStatus.PRESENT;
    }

    private void sendSessionFinalSummary(CalendarEvent event,
                                         FinalAttendanceNames attendanceNames,
                                         UnknownParticipantClassification unknowns) {
        List<String> knownButUnexpectedNames = mapUnknownDisplayNames(unknowns.knownButUnexpected());
        List<String> notInSystemNames = mapUnknownDisplayNames(unknowns.notInSystem());
        notificationService.notify(NotificationType.SESSION_FINAL_SUMMARY, event,
                new SessionFinalSummarySubject(
                        attendanceNames.presentNames(),
                        attendanceNames.lateNames(),
                        attendanceNames.absentNames(),
                        knownButUnexpectedNames,
                        notInSystemNames));
    }

    private List<String> mapUnknownDisplayNames(List<UnknownParticipantEntry> entries) {
        return entries.stream()
                .map(UnknownParticipantEntry::displayName)
                .toList();
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

    private void addNameByStatus(String name, AttendanceStatus status, List<String> presentNames,
                                 List<String> lateNames, List<String> absentNames) {
        if (status == AttendanceStatus.LATE) {
            lateNames.add(name);
            return;
        }
        if (status == AttendanceStatus.PRESENT) {
            presentNames.add(name);
            return;
        }
        absentNames.add(name);
    }

    private void forEachExpectedPerson(ExpectedParticipants expected, BiConsumer<Person, PersonType> consumer) {
        for (Person student : expected.students()) {
            consumer.accept(student, PersonType.STUDENT);
        }
        for (Person teacher : expected.teachers()) {
            consumer.accept(teacher, PersonType.TEACHER);
        }
    }

    private record ParticipantHistory(
            List<MeetParticipant> participants,
            Map<String, Instant> joinTimeByUserId,
            Map<String, Instant> joinTimeByDisplayName
    ) {}

    private record FinalAttendanceNames(
            List<String> presentNames,
            List<String> lateNames,
            List<String> absentNames
    ) {}
}
