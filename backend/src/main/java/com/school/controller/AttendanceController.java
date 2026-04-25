package com.school.controller;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.school.dto.AttendanceRecordResponse;
import com.school.dto.AttendanceSummaryResponse;
import com.school.dto.StudentAttendanceRecord;
import com.school.entity.AttendanceStatus;
import com.school.entity.Person;
import com.school.entity.PersonType;
import com.school.integration.MeetClient;
import com.school.integration.MeetParticipant;
import com.school.model.CalendarEvent;
import com.school.repository.PersonRepository;
import com.school.service.AttendanceService;
import com.school.service.CalendarSyncService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/attendance")
public class AttendanceController {

    private final CalendarSyncService calendarSyncService;
    private final PersonRepository personRepository;
    private final AttendanceService attendanceService;
    private final MeetClient meetClient;
    private final String principalGoogleUserId;

    public AttendanceController(CalendarSyncService calendarSyncService,
                                PersonRepository personRepository,
                                AttendanceService attendanceService,
                                MeetClient meetClient,
                                @Value("${app.principal.google-user-id:}") String principalGoogleUserId) {
        this.calendarSyncService = calendarSyncService;
        this.personRepository = personRepository;
        this.attendanceService = attendanceService;
        this.meetClient = meetClient;
        this.principalGoogleUserId = principalGoogleUserId;
    }

    @GetMapping("/today")
    public ResponseEntity<List<AttendanceSummaryResponse>> getToday(
            @RequestParam(defaultValue = "false") boolean live) throws Exception {
        return ResponseEntity.ok(attendanceService.getToday(live));
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

    @GetMapping("/today/space-codes")
    public ResponseEntity<Map<String, String>> getTodaySpaceCodes() throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        for (var event : calendarSyncService.getTodaysEvents()) {
            result.put(event.getTitle(), event.getSpaceCode());
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/debug/{spaceCode}")
    public ResponseEntity<Map<String, Object>> debugParticipants(@PathVariable String spaceCode) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("spaceCode", spaceCode);

        Boolean active = null;
        try {
            active = meetClient.isMeetingActive(spaceCode);
        } catch (Exception e) {
            out.put("isMeetingActiveError", e.getMessage());
        }
        out.put("meetingActive", active);

        List<Map<String, Object>> rawParticipants = new ArrayList<>();
        if (Boolean.TRUE.equals(active)) {
            try {
                List<MeetParticipant> participants = meetClient.getActiveParticipants(spaceCode);
                for (MeetParticipant p : participants) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("googleUserId", p.googleUserId());
                    entry.put("displayName", p.displayName());
                    Person person = resolveParticipantToPerson(p, PersonType.STUDENT);
                    if (person == null) {
                        person = resolveParticipantToPerson(p, PersonType.TEACHER);
                    }
                    if (person != null) {
                        entry.put("resolvedAs", person.getPersonType().name());
                        entry.put("resolvedName", person.getName());
                        entry.put("resolvedId", person.getId());
                    } else {
                        log.warn("Unregistered participant in debug view for space {}: googleUserId={}, displayName={}",
                                spaceCode, p.googleUserId(), p.displayName());
                    }
                    rawParticipants.add(entry);
                }
            } catch (Exception e) {
                out.put("getParticipantsError", e.getMessage());
            }
        }
        out.put("participants", rawParticipants);
        out.put("participantCount", rawParticipants.size());

        try {
            List<Map<String, Object>> eventAttendees = new ArrayList<>();
            for (var event : calendarSyncService.getTodaysEvents()) {
                if (!spaceCode.equals(event.getSpaceCode())) continue;
                if (event.getAttendeeEmails() == null) continue;
                for (String email : event.getAttendeeEmails()) {
                    Map<String, Object> ae = new LinkedHashMap<>();
                    ae.put("email", email);
                    Person person = personRepository.findByMeetEmail(email)
                            .orElse(null);
                    if (person != null) {
                        ae.put("registeredAs", person.getPersonType().name());
                        ae.put("name", person.getName());
                        ae.put("googleUserId", person.getGoogleUserId());
                        ae.put("meetDisplayName", person.getMeetDisplayName());
                    } else {
                        ae.put("registeredAs", "UNREGISTERED");
                    }
                    eventAttendees.add(ae);
                }
            }
            out.put("calendarAttendees", eventAttendees);
        } catch (Exception e) {
            out.put("calendarAttendeesError", e.getMessage());
        }

        log.info("DEBUG /attendance/debug/{} -> {}", spaceCode, out);
        return ResponseEntity.ok(out);
    }

    @PostMapping("/upsert")
    public ResponseEntity<?> upsert(@RequestBody UpsertAttendanceRequest req) {
        LocalDate date = req.date() != null ? req.date() : LocalDate.now();
        try {
            attendanceService.upsert(
                    req.personId(),
                    req.personType(),
                    req.calendarEventId(),
                    req.eventTitle(),
                    date,
                    req.status(),
                    req.autoResolveFromMeet());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", e.getMessage()));
        }
        return ResponseEntity.ok().build();
    }

    public record UpsertAttendanceRequest(
            Long personId,
            PersonType personType,
            String calendarEventId,
            String eventTitle,
            LocalDate date,
            AttendanceStatus status,
            Boolean autoResolveFromMeet
    ) {}

    @GetMapping("/records")
    public ResponseEntity<List<AttendanceRecordResponse>> getRecords(
            @RequestParam(required = false) PersonType personType,
            @RequestParam(required = false) Long personId,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo,
            @RequestParam(required = false) List<AttendanceStatus> status) {
        return ResponseEntity.ok(attendanceService.getRecords(personType, personId, dateFrom, dateTo, status));
    }

    @GetMapping("/student/{id}")
    public ResponseEntity<List<StudentAttendanceRecord>> getByStudent(@PathVariable Long id) {
        return ResponseEntity.ok(attendanceService.getByStudent(id));
    }
}
