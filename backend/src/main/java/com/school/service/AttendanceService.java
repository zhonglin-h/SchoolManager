package com.school.service;

import com.school.dto.AttendanceRecordResponse;
import com.school.dto.AttendanceSummaryResponse;
import com.school.dto.StudentAttendanceRecord;
import com.school.entity.Attendance;
import com.school.entity.AttendanceStatus;
import com.school.entity.Person;
import com.school.entity.PersonType;
import com.school.integration.MeetClient;
import com.school.integration.MeetParticipant;
import com.school.model.CalendarEvent;
import com.school.repository.AttendanceRepository;
import com.school.repository.PersonRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class AttendanceService {

    private final AttendanceRepository attendanceRepository;
    private final PersonRepository personRepository;
    private final CalendarSyncService calendarSyncService;
    private final MeetClient meetClient;
    private final String principalEmail;
    private final String principalGoogleUserId;
    private final int lateBufferMinutes;

    public AttendanceService(AttendanceRepository attendanceRepository,
                             PersonRepository personRepository,
                             CalendarSyncService calendarSyncService,
                             MeetClient meetClient,
                             @Value("${app.principal.email}") String principalEmail,
                             @Value("${app.principal.google-user-id:}") String principalGoogleUserId,
                             @Value("${app.attendance.late-buffer-minutes}") int lateBufferMinutes) {
        this.attendanceRepository = attendanceRepository;
        this.personRepository = personRepository;
        this.calendarSyncService = calendarSyncService;
        this.meetClient = meetClient;
        this.principalEmail = principalEmail;
        this.principalGoogleUserId = principalGoogleUserId;
        this.lateBufferMinutes = lateBufferMinutes;
    }

    public List<AttendanceSummaryResponse> getToday(boolean live) throws Exception {
        List<CalendarEvent> events = calendarSyncService.getTodaysEvents();
        LocalDate today = LocalDate.now();
        List<AttendanceSummaryResponse> summaries = new ArrayList<>();

        for (CalendarEvent event : events) {
            List<Attendance> records = attendanceRepository.findByCalendarEventIdAndDate(event.getId(), today);
            Map<Long, Attendance> byPersonId = records.stream()
                    .filter(a -> a.getPerson() != null)
                    .collect(Collectors.toMap(a -> a.getPerson().getId(), Function.identity()));

            Boolean meetingActive = null;
            List<MeetParticipant> liveParticipants = Collections.emptyList();
            if (live) {
                try {
                    meetingActive = meetClient.isMeetingActive(event.getSpaceCode());
                    if (Boolean.TRUE.equals(meetingActive)) {
                        liveParticipants = meetClient.getActiveParticipants(event.getSpaceCode());
                    }
                } catch (Exception ignored) {}
            }

            Set<String> liveGoogleIds = new HashSet<>();
            Set<String> liveDisplayNames = new HashSet<>();
            Map<String, Instant> joinByUserId = new HashMap<>();
            Map<String, Instant> joinByDisplayName = new HashMap<>();
            for (MeetParticipant p : liveParticipants) {
                if (p.googleUserId() != null) {
                    liveGoogleIds.add(p.googleUserId());
                    if (p.earliestStartTime() != null) joinByUserId.put(p.googleUserId(), p.earliestStartTime());
                }
                if (p.displayName() != null) {
                    liveDisplayNames.add(p.displayName().toLowerCase());
                    if (p.earliestStartTime() != null) joinByDisplayName.put(p.displayName().toLowerCase(), p.earliestStartTime());
                }
            }
            Instant classStart = event.getStartTime().atZone(ZoneId.systemDefault()).toInstant();

            List<AttendanceSummaryResponse.AttendanceEntry> entries = new ArrayList<>();
            int present = 0;

            if (event.getAttendeeEmails() != null) {
                for (String email : event.getAttendeeEmails()) {
                    if (email.equalsIgnoreCase(principalEmail)) continue;
                    Person person = personRepository.findByMeetEmailAndActiveTrue(email)
                            .orElse(null);
                    if (person != null) {
                        Attendance att = byPersonId.get(person.getId());
                        AttendanceStatus status = att != null ? att.getStatus() : null;
                        boolean inMeetNow = isInMeet(person, liveGoogleIds, liveDisplayNames);
                        if (status == null && inMeetNow) {
                            status = writeAttendance(person, event, today,
                                    resolveJoinTime(person.getGoogleUserId(), person.getMeetDisplayName(),
                                            person.getName(), joinByUserId, joinByDisplayName),
                                    classStart);
                        }
                        entries.add(new AttendanceSummaryResponse.AttendanceEntry(
                                person.getId(), person.getPersonType(), person.getName(), email, status, true, inMeetNow));
                    } else {
                        entries.add(new AttendanceSummaryResponse.AttendanceEntry(
                                null, null, email, email, null, false, false));
                    }
                }
            }

            for (AttendanceSummaryResponse.AttendanceEntry entry : entries) {
                if (entry.status() == AttendanceStatus.PRESENT || entry.status() == AttendanceStatus.LATE) present++;
            }

            int totalExpected = entries.size();
            Set<String> coveredRefs = new HashSet<>();
            for (var entry : entries) {
                if (entry.personId() != null && entry.personType() != null) {
                    coveredRefs.add(entry.personType().name() + ":" + entry.personId());
                }
            }
            personRepository.findByMeetEmailAndActiveTrue(principalEmail)
                    .ifPresent(p -> coveredRefs.add(p.getPersonType().name() + ":" + p.getId()));

            List<AttendanceSummaryResponse.GuestEntry> guests = new ArrayList<>();
            for (MeetParticipant p : liveParticipants) {
                if (!principalGoogleUserId.isBlank() && principalGoogleUserId.equals(p.googleUserId())) continue;
                Person person = resolveParticipantToPerson(p, PersonType.STUDENT);
                if (person == null) {
                    person = resolveParticipantToPerson(p, PersonType.TEACHER);
                }
                if (person != null) {
                    if (!coveredRefs.contains(person.getPersonType().name() + ":" + person.getId())) {
                        guests.add(new AttendanceSummaryResponse.GuestEntry(
                                p.googleUserId(), p.displayName(),
                                person.getId(), person.getPersonType(), person.getName()));
                    }
                    continue;
                }
                guests.add(new AttendanceSummaryResponse.GuestEntry(
                        p.googleUserId(), p.displayName(), null, null, null));
            }

            summaries.add(new AttendanceSummaryResponse(
                    event.getId(),
                    event.getSpaceCode(),
                    event.getTitle(),
                    today,
                    event.getStartTime().toLocalTime(),
                    event.getEndTime().toLocalTime(),
                    meetingActive,
                    totalExpected,
                    present,
                    0,
                    0,
                    entries,
                    guests
            ));
        }

        summaries.sort(java.util.Comparator
                .comparing(AttendanceSummaryResponse::startTime)
                .thenComparing(AttendanceSummaryResponse::eventTitle));
        return summaries;
    }

    public void upsert(Long personId,
                       PersonType personType,
                       String calendarEventId,
                       String eventTitle,
                       LocalDate date,
                       AttendanceStatus status,
                       Boolean autoResolveFromMeet) {
        Person person = personRepository.findById(personId)
                .orElseThrow(() -> new RuntimeException("Person not found: " + personId));
        if (person.getPersonType() != personType) {
            throw new IllegalArgumentException("personType does not match persisted person type");
        }

        Attendance att = attendanceRepository
                .findByPersonIdAndCalendarEventIdAndDate(personId, calendarEventId, date)
                .orElseGet(() -> Attendance.builder()
                        .person(person)
                        .calendarEventId(calendarEventId)
                        .date(date)
                        .build());

        CalendarEvent todayEvent = findTodayEvent(calendarEventId).orElse(null);
        String resolvedEventTitle = eventTitle != null
                ? eventTitle
                : todayEvent != null ? todayEvent.getTitle() : null;

        AttendanceStatus resolvedStatus = status;
        if (Boolean.TRUE.equals(autoResolveFromMeet) && resolvedStatus != null && todayEvent != null) {
            resolvedStatus = resolveStatusFromMeet(person, todayEvent, resolvedStatus);
        }
        if (resolvedStatus == null) {
            throw new IllegalArgumentException("status is required");
        }

        att.setStatus(resolvedStatus);
        if (resolvedEventTitle != null) {
            att.setEventTitle(resolvedEventTitle);
        }
        attendanceRepository.save(att);
    }

    public List<AttendanceRecordResponse> getRecords(PersonType personType,
                                                     Long personId,
                                                     LocalDate dateFrom,
                                                     LocalDate dateTo,
                                                     List<AttendanceStatus> status) {
        List<Attendance> records;
        if (personId != null) {
            records = attendanceRepository.findByPersonIdOrderByDateDescIdDesc(personId);
        } else if (personType != null) {
            records = attendanceRepository.findByPersonTypeOrderByDateDescIdDesc(personType);
        } else {
            records = attendanceRepository.findAllOrderByDateDescIdDesc();
        }

        Stream<Attendance> stream = records.stream();
        if (personType != null) {
            stream = stream.filter(a -> a.getPerson() != null && a.getPerson().getPersonType() == personType);
        }
        if (dateFrom != null) {
            stream = stream.filter(a -> !a.getDate().isBefore(dateFrom));
        }
        if (dateTo != null) {
            stream = stream.filter(a -> !a.getDate().isAfter(dateTo));
        }
        if (status != null && !status.isEmpty()) {
            Set<AttendanceStatus> statusSet = new HashSet<>(status);
            stream = stream.filter(a -> statusSet.contains(a.getStatus()));
        }
        records = stream.toList();

        return records.stream()
                .map(a -> new AttendanceRecordResponse(
                        a.getId(),
                        a.getPerson() != null ? a.getPerson().getId() : null,
                        a.getPerson() != null ? a.getPerson().getPersonType() : null,
                        a.getPerson() != null ? a.getPerson().getName() : null,
                        a.getCalendarEventId(),
                        a.getEventTitle(),
                        a.getDate(),
                        a.getStatus(),
                        a.getUpdatedAt()))
                .toList();
    }

    public List<StudentAttendanceRecord> getByStudent(Long id) {
        return attendanceRepository.findByPersonId(id).stream()
                .filter(a -> a.getPerson() != null && a.getPerson().getPersonType() == PersonType.STUDENT)
                .map(a -> new StudentAttendanceRecord(a.getCalendarEventId(), a.getDate(), a.getStatus()))
                .toList();
    }

    private Optional<CalendarEvent> findTodayEvent(String calendarEventId) {
        try {
            return calendarSyncService.getTodaysEvents().stream()
                    .filter(e -> e.getId().equals(calendarEventId))
                    .findFirst();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private AttendanceStatus resolveStatusFromMeet(Person person, CalendarEvent event, AttendanceStatus fallback) {
        Map<String, Instant> joinByUserId = new HashMap<>();
        Map<String, Instant> joinByDisplayName = new HashMap<>();
        try {
            List<MeetParticipant> participants = meetClient.getAllParticipants(event.getSpaceCode());
            for (MeetParticipant p : participants) {
                if (p.earliestStartTime() == null) continue;
                if (p.googleUserId() != null) {
                    joinByUserId.put(p.googleUserId(), p.earliestStartTime());
                }
                if (p.displayName() != null) {
                    joinByDisplayName.put(p.displayName().toLowerCase(), p.earliestStartTime());
                }
            }
        } catch (Exception e) {
            return fallback;
        }

        Instant joinTime = resolveJoinTime(
                person.getGoogleUserId(),
                person.getMeetDisplayName(),
                person.getName(),
                joinByUserId,
                joinByDisplayName);
        if (joinTime == null) return fallback;

        Instant lateThreshold = event.getStartTime().atZone(ZoneId.systemDefault()).toInstant()
                .plusSeconds(lateBufferMinutes * 60L);
        return joinTime.isAfter(lateThreshold) ? AttendanceStatus.LATE : AttendanceStatus.PRESENT;
    }

    private AttendanceStatus statusFromJoinTime(Instant joinTime, Instant classStart) {
        return (joinTime != null && joinTime.isAfter(classStart)) ? AttendanceStatus.LATE : AttendanceStatus.PRESENT;
    }

    private AttendanceStatus writeAttendance(Person person, CalendarEvent event, LocalDate date,
                                             Instant joinTime, Instant classStart) {
        AttendanceStatus status = statusFromJoinTime(joinTime, classStart);
        attendanceRepository.findByPersonIdAndCalendarEventIdAndDate(person.getId(), event.getId(), date)
                .ifPresentOrElse(existing -> {}, () -> attendanceRepository.save(
                        Attendance.builder().person(person).calendarEventId(event.getId())
                                .eventTitle(event.getTitle())
                                .date(date).status(status).build()));
        return status;
    }

    private Person resolveParticipantToPerson(MeetParticipant p, PersonType personType) {
        Person person = p.googleUserId() != null
                ? personRepository.findByPersonTypeAndGoogleUserIdAndActiveTrue(personType, p.googleUserId()).orElse(null)
                : null;
        if (person == null && p.displayName() != null) {
            person = personRepository.findByPersonTypeAndMeetDisplayNameIgnoreCaseAndActiveTrue(personType, p.displayName())
                    .or(() -> personRepository.findByPersonTypeAndNameIgnoreCaseAndActiveTrue(personType, p.displayName()))
                    .orElse(null);
        }
        return person;
    }

    private boolean isInMeet(Person person, Set<String> liveGoogleIds, Set<String> liveDisplayNames) {
        if (person.getGoogleUserId() != null && liveGoogleIds.contains(person.getGoogleUserId())) return true;
        if (person.getMeetDisplayName() != null && liveDisplayNames.contains(person.getMeetDisplayName().toLowerCase())) return true;
        return liveDisplayNames.contains(person.getName().toLowerCase());
    }

    private Instant resolveJoinTime(String googleUserId, String meetDisplayName, String name,
                                    Map<String, Instant> byUserId, Map<String, Instant> byDisplayName) {
        if (googleUserId != null && byUserId.containsKey(googleUserId)) return byUserId.get(googleUserId);
        if (meetDisplayName != null && byDisplayName.containsKey(meetDisplayName.toLowerCase())) return byDisplayName.get(meetDisplayName.toLowerCase());
        if (name != null && byDisplayName.containsKey(name.toLowerCase())) return byDisplayName.get(name.toLowerCase());
        return null;
    }
}
