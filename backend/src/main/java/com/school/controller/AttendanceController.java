package com.school.controller;

import com.school.dto.AttendanceSummaryResponse;
import com.school.entity.Attendance;
import com.school.entity.AttendanceStatus;
import com.school.entity.PersonType;
import com.school.entity.Student;
import com.school.entity.Teacher;
import com.school.integration.MeetClient;
import com.school.integration.MeetParticipant;
import com.school.model.CalendarEvent;
import com.school.repository.AttendanceRepository;
import com.school.repository.StudentRepository;
import com.school.repository.TeacherRepository;
import com.school.service.CalendarSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@RestController
@RequestMapping("/attendance")
public class AttendanceController {

    private final CalendarSyncService calendarSyncService;
    private final AttendanceRepository attendanceRepository;
    private final StudentRepository studentRepository;
    private final TeacherRepository teacherRepository;
    private final MeetClient meetClient;
    private final String principalEmail;
    private final String principalGoogleUserId;

    public AttendanceController(CalendarSyncService calendarSyncService,
                                AttendanceRepository attendanceRepository,
                                StudentRepository studentRepository,
                                TeacherRepository teacherRepository,
                                MeetClient meetClient,
                                @Value("${app.principal.email}") String principalEmail,
                                @Value("${app.principal.google-user-id:}") String principalGoogleUserId) {
        this.calendarSyncService = calendarSyncService;
        this.attendanceRepository = attendanceRepository;
        this.studentRepository = studentRepository;
        this.teacherRepository = teacherRepository;
        this.meetClient = meetClient;
        this.principalEmail = principalEmail;
        this.principalGoogleUserId = principalGoogleUserId;
    }

    @GetMapping("/today")
    public ResponseEntity<List<AttendanceSummaryResponse>> getToday(
            @RequestParam(defaultValue = "false") boolean live) throws Exception {
        List<CalendarEvent> events = calendarSyncService.getTodaysEvents();
        LocalDate today = LocalDate.now();
        List<AttendanceSummaryResponse> summaries = new ArrayList<>();

        for (CalendarEvent event : events) {
            List<Attendance> records = attendanceRepository
                    .findByCalendarEventIdAndDate(event.getId(), today);

            Map<Long, Attendance> byStudentId = records.stream()
                    .filter(a -> a.getStudent() != null)
                    .collect(Collectors.toMap(a -> a.getStudent().getId(), Function.identity()));

            Map<Long, Attendance> byTeacherId = records.stream()
                    .filter(a -> a.getTeacher() != null)
                    .collect(Collectors.toMap(a -> a.getTeacher().getId(), Function.identity()));

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

            // Build lookup sets and join-time maps for live participant matching
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
                    var studentOpt = studentRepository.findByMeetEmailAndActiveTrue(email);
                    if (studentOpt.isPresent()) {
                        var student = studentOpt.get();
                        Attendance att = byStudentId.get(student.getId());
                        AttendanceStatus status = att != null ? att.getStatus() : null;
                        boolean inMeetNow = isInMeet(student, liveGoogleIds, liveDisplayNames);
                        if (status == null && inMeetNow) {
                            status = writeStudentAttendance(student, event, today,
                                    resolveJoinTime(student.getGoogleUserId(), student.getMeetDisplayName(),
                                            student.getName(), joinByUserId, joinByDisplayName),
                                    classStart);
                        }
                        entries.add(new AttendanceSummaryResponse.AttendanceEntry(
                                student.getId(), PersonType.STUDENT, student.getName(), email, status, true, inMeetNow));
                    } else {
                        var teacherOpt = teacherRepository.findByMeetEmailAndActiveTrue(email);
                        if (teacherOpt.isPresent()) {
                            var teacher = teacherOpt.get();
                            Attendance att = byTeacherId.get(teacher.getId());
                            AttendanceStatus status = att != null ? att.getStatus() : null;
                            boolean inMeetNow = isInMeet(teacher, liveGoogleIds, liveDisplayNames);
                            if (status == null && inMeetNow) {
                                status = writeTeacherAttendance(teacher, event, today,
                                        resolveJoinTime(teacher.getGoogleUserId(), teacher.getMeetDisplayName(),
                                                teacher.getName(), joinByUserId, joinByDisplayName),
                                        classStart);
                            }
                            entries.add(new AttendanceSummaryResponse.AttendanceEntry(
                                    teacher.getId(), PersonType.TEACHER, teacher.getName(), email, status, true, inMeetNow));
                        } else {
                            entries.add(new AttendanceSummaryResponse.AttendanceEntry(
                                    null, null, email, email, null, false, false));
                        }
                    }
                }
            }

            for (AttendanceSummaryResponse.AttendanceEntry entry : entries) {
                if (entry.status() == AttendanceStatus.PRESENT || entry.status() == AttendanceStatus.LATE) present++;
            }

            int totalExpected = entries.size();

            // Guests: live participants not covered by the attendee list
            Set<String> coveredRefs = new HashSet<>();
            for (var entry : entries) {
                if (entry.personId() != null && entry.personType() != null) {
                    coveredRefs.add(entry.personType().name() + ":" + entry.personId());
                }
            }
            // Also exclude the principal (skipped from entries but may still be in the meeting)
            studentRepository.findByMeetEmailAndActiveTrue(principalEmail)
                    .ifPresent(s -> coveredRefs.add(PersonType.STUDENT.name() + ":" + s.getId()));
            teacherRepository.findByMeetEmailAndActiveTrue(principalEmail)
                    .ifPresent(t -> coveredRefs.add(PersonType.TEACHER.name() + ":" + t.getId()));
            List<AttendanceSummaryResponse.GuestEntry> guests = new ArrayList<>();
            for (MeetParticipant p : liveParticipants) {
                if (!principalGoogleUserId.isBlank() && principalGoogleUserId.equals(p.googleUserId())) continue;
                var studentOpt = p.googleUserId() != null
                        ? studentRepository.findByGoogleUserIdAndActiveTrue(p.googleUserId()) : java.util.Optional.<Student>empty();
                if (studentOpt.isEmpty() && p.displayName() != null) {
                    studentOpt = studentRepository.findByMeetDisplayNameIgnoreCaseAndActiveTrue(p.displayName())
                            .or(() -> studentRepository.findByNameIgnoreCaseAndActiveTrue(p.displayName()));
                }
                if (studentOpt.isPresent()) {
                    if (!coveredRefs.contains(PersonType.STUDENT.name() + ":" + studentOpt.get().getId())) {
                        guests.add(new AttendanceSummaryResponse.GuestEntry(
                                p.googleUserId(), p.displayName(),
                                studentOpt.get().getId(), PersonType.STUDENT, studentOpt.get().getName()));
                    }
                    continue;
                }
                var teacherOpt = p.googleUserId() != null
                        ? teacherRepository.findByGoogleUserIdAndActiveTrue(p.googleUserId()) : java.util.Optional.<Teacher>empty();
                if (teacherOpt.isEmpty() && p.displayName() != null) {
                    teacherOpt = teacherRepository.findByMeetDisplayNameIgnoreCaseAndActiveTrue(p.displayName())
                            .or(() -> teacherRepository.findByNameIgnoreCaseAndActiveTrue(p.displayName()));
                }
                if (teacherOpt.isPresent()) {
                    if (!coveredRefs.contains(PersonType.TEACHER.name() + ":" + teacherOpt.get().getId())) {
                        guests.add(new AttendanceSummaryResponse.GuestEntry(
                                p.googleUserId(), p.displayName(),
                                teacherOpt.get().getId(), PersonType.TEACHER, teacherOpt.get().getName()));
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

        return ResponseEntity.ok(summaries);
    }

    private Instant resolveJoinTime(String googleUserId, String meetDisplayName, String name,
                                    Map<String, Instant> byUserId, Map<String, Instant> byDisplayName) {
        if (googleUserId != null && byUserId.containsKey(googleUserId)) return byUserId.get(googleUserId);
        if (meetDisplayName != null && byDisplayName.containsKey(meetDisplayName.toLowerCase())) return byDisplayName.get(meetDisplayName.toLowerCase());
        if (name != null && byDisplayName.containsKey(name.toLowerCase())) return byDisplayName.get(name.toLowerCase());
        return null;
    }

    private AttendanceStatus statusFromJoinTime(Instant joinTime, Instant classStart) {
        return (joinTime != null && joinTime.isAfter(classStart)) ? AttendanceStatus.LATE : AttendanceStatus.PRESENT;
    }

    private AttendanceStatus writeStudentAttendance(Student student, CalendarEvent event, LocalDate date,
                                                     Instant joinTime, Instant classStart) {
        AttendanceStatus status = statusFromJoinTime(joinTime, classStart);
        attendanceRepository.findByStudentIdAndCalendarEventIdAndDate(student.getId(), event.getId(), date)
                .ifPresentOrElse(existing -> {}, () -> attendanceRepository.save(
                        Attendance.builder().student(student).calendarEventId(event.getId())
                                .eventTitle(event.getTitle())
                                .date(date).status(status).build()));
        return status;
    }

    private AttendanceStatus writeTeacherAttendance(Teacher teacher, CalendarEvent event, LocalDate date,
                                                     Instant joinTime, Instant classStart) {
        AttendanceStatus status = statusFromJoinTime(joinTime, classStart);
        attendanceRepository.findByTeacherIdAndCalendarEventIdAndDate(teacher.getId(), event.getId(), date)
                .ifPresentOrElse(existing -> {}, () -> attendanceRepository.save(
                        Attendance.builder().teacher(teacher).calendarEventId(event.getId())
                                .eventTitle(event.getTitle())
                                .date(date).status(status).build()));
        return status;
    }

    private boolean isInMeet(Student student, Set<String> liveGoogleIds, Set<String> liveDisplayNames) {
        if (student.getGoogleUserId() != null && liveGoogleIds.contains(student.getGoogleUserId())) return true;
        if (student.getMeetDisplayName() != null && liveDisplayNames.contains(student.getMeetDisplayName().toLowerCase())) return true;
        return liveDisplayNames.contains(student.getName().toLowerCase());
    }

    private boolean isInMeet(Teacher teacher, Set<String> liveGoogleIds, Set<String> liveDisplayNames) {
        if (teacher.getGoogleUserId() != null && liveGoogleIds.contains(teacher.getGoogleUserId())) return true;
        if (teacher.getMeetDisplayName() != null && liveDisplayNames.contains(teacher.getMeetDisplayName().toLowerCase())) return true;
        return liveDisplayNames.contains(teacher.getName().toLowerCase());
    }

    /** Returns the spaceCode for each today event — use with the debug endpoint. */
    @GetMapping("/today/space-codes")
    public ResponseEntity<Map<String, String>> getTodaySpaceCodes() throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        for (var event : calendarSyncService.getTodaysEvents()) {
            result.put(event.getTitle(), event.getSpaceCode());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Debug endpoint — call GET /attendance/debug/{spaceCode} to see exactly what the Meet API
     * returns for active participants in that space, plus how each participant resolves against
     * the teacher/student DB records for the matching calendar event attendees.
     *
     * Example: GET /attendance/debug/abc-defg-hij
     */
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

                    // Resolve against DB
                    var student = p.googleUserId() != null
                            ? studentRepository.findByGoogleUserId(p.googleUserId()).orElse(null) : null;
                    if (student == null && p.displayName() != null) {
                        student = studentRepository.findByMeetDisplayNameIgnoreCase(p.displayName())
                                .or(() -> studentRepository.findByNameIgnoreCase(p.displayName()))
                                .orElse(null);
                    }
                    if (student != null) {
                        entry.put("resolvedAs", "STUDENT");
                        entry.put("resolvedName", student.getName());
                        entry.put("resolvedId", student.getId());
                    } else {
                        var teacher = p.googleUserId() != null
                                ? teacherRepository.findByGoogleUserId(p.googleUserId()).orElse(null) : null;
                        if (teacher == null && p.displayName() != null) {
                            teacher = teacherRepository.findByMeetDisplayNameIgnoreCase(p.displayName())
                                    .or(() -> teacherRepository.findByNameIgnoreCase(p.displayName()))
                                    .orElse(null);
                        }
                        if (teacher != null) {
                            entry.put("resolvedAs", "TEACHER");
                            entry.put("resolvedName", teacher.getName());
                            entry.put("resolvedId", teacher.getId());
                        } else {
                            entry.put("resolvedAs", "UNREGISTERED");
                        }
                    }
                    rawParticipants.add(entry);
                }
            } catch (Exception e) {
                out.put("getParticipantsError", e.getMessage());
            }
        }
        out.put("participants", rawParticipants);
        out.put("participantCount", rawParticipants.size());

        // Also show what the calendar event attendees look like from the DB perspective
        try {
            List<Map<String, Object>> eventAttendees = new ArrayList<>();
            for (var event : calendarSyncService.getTodaysEvents()) {
                if (!spaceCode.equals(event.getSpaceCode())) continue;
                if (event.getAttendeeEmails() == null) continue;
                for (String email : event.getAttendeeEmails()) {
                    Map<String, Object> ae = new LinkedHashMap<>();
                    ae.put("email", email);
                    var s = studentRepository.findByMeetEmail(email).orElse(null);
                    var t = teacherRepository.findByMeetEmail(email).orElse(null);
                    if (s != null) {
                        ae.put("registeredAs", "STUDENT");
                        ae.put("name", s.getName());
                        ae.put("googleUserId", s.getGoogleUserId());
                        ae.put("meetDisplayName", s.getMeetDisplayName());
                    } else if (t != null) {
                        ae.put("registeredAs", "TEACHER");
                        ae.put("name", t.getName());
                        ae.put("googleUserId", t.getGoogleUserId());
                        ae.put("meetDisplayName", t.getMeetDisplayName());
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

    /**
     * Upserts an attendance record for a student or teacher.
     * If a record already exists for the given person/event/date it is updated; otherwise created.
     */
    @PostMapping("/upsert")
    public ResponseEntity<Void> upsert(@RequestBody UpsertAttendanceRequest req) {
        LocalDate date = req.date() != null ? req.date() : LocalDate.now();
        if (PersonType.STUDENT == req.personType()) {
            Student student = studentRepository.findById(req.personId())
                    .orElseThrow(() -> new RuntimeException("Student not found: " + req.personId()));
            Attendance att = attendanceRepository
                    .findByStudentIdAndCalendarEventIdAndDate(req.personId(), req.calendarEventId(), date)
                    .orElseGet(() -> Attendance.builder()
                            .student(student)
                            .calendarEventId(req.calendarEventId())
                            .date(date)
                            .build());
            att.setStatus(req.status());
            if (req.eventTitle() != null) {
                att.setEventTitle(req.eventTitle());
            }
            attendanceRepository.save(att);
        } else if (PersonType.TEACHER == req.personType()) {
            Teacher teacher = teacherRepository.findById(req.personId())
                    .orElseThrow(() -> new RuntimeException("Teacher not found: " + req.personId()));
            Attendance att = attendanceRepository
                    .findByTeacherIdAndCalendarEventIdAndDate(req.personId(), req.calendarEventId(), date)
                    .orElseGet(() -> Attendance.builder()
                            .teacher(teacher)
                            .calendarEventId(req.calendarEventId())
                            .date(date)
                            .build());
            att.setStatus(req.status());
            if (req.eventTitle() != null) {
                att.setEventTitle(req.eventTitle());
            }
            attendanceRepository.save(att);
        } else {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok().build();
    }

    public record UpsertAttendanceRequest(
            Long personId,
            PersonType personType,
            String calendarEventId,
            String eventTitle,
            LocalDate date,
            AttendanceStatus status
    ) {}

    /**
     * Returns attendance records with optional filters.
     *
     * @param personType ALL (default), STUDENT, or TEACHER
     * @param personId   optional — if provided, only records for this person are returned
     * @param dateFrom   optional — only records on or after this date (ISO date, e.g. 2024-01-01)
     * @param dateTo     optional — only records on or before this date (ISO date, e.g. 2024-12-31)
     * @param status     optional — repeated parameter for filtering by status (e.g. ?status=ABSENT&status=LATE); if omitted all statuses are returned
     */
    @GetMapping("/records")
    public ResponseEntity<List<AttendanceRecordResponse>> getRecords(
            @RequestParam(defaultValue = "ALL") String personType,
            @RequestParam(required = false) Long personId,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo,
            @RequestParam(required = false) List<AttendanceStatus> status) {

        List<Attendance> records;

        if (personId != null) {
            if (PersonType.TEACHER.name().equals(personType)) {
                records = attendanceRepository.findByTeacherIdOrderByDateDescIdDesc(personId);
            } else {
                records = attendanceRepository.findByStudentIdOrderByDateDescIdDesc(personId);
            }
        } else if (PersonType.STUDENT.name().equals(personType)) {
            records = attendanceRepository.findByStudentNotNullOrderByDateDescIdDesc();
        } else if (PersonType.TEACHER.name().equals(personType)) {
            records = attendanceRepository.findByTeacherNotNullOrderByDateDescIdDesc();
        } else {
            records = attendanceRepository.findAllOrderByDateDescIdDesc();
        }

        // Apply optional date range and status filters in memory
        Stream<Attendance> stream = records.stream();
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
        records = stream.collect(Collectors.toList());

        List<AttendanceRecordResponse> result = records.stream()
                .map(a -> {
                    Long pid;
                    PersonType ptype;
                    String pname;
                    if (a.getStudent() != null) {
                        pid = a.getStudent().getId();
                        ptype = PersonType.STUDENT;
                        pname = a.getStudent().getName();
                    } else {
                        pid = a.getTeacher() != null ? a.getTeacher().getId() : null;
                        ptype = PersonType.TEACHER;
                        pname = a.getTeacher() != null ? a.getTeacher().getName() : null;
                    }
                    return new AttendanceRecordResponse(
                            a.getId(), pid, ptype, pname,
                            a.getCalendarEventId(), a.getEventTitle(),
                            a.getDate(), a.getStatus(), a.getUpdatedAt());
                })
                .toList();

        return ResponseEntity.ok(result);
    }

    public record AttendanceRecordResponse(
            Long id,
            Long personId,
            PersonType personType,
            String personName,
            String calendarEventId,
            String eventTitle,
            LocalDate date,
            AttendanceStatus status,
            java.time.LocalDateTime updatedAt
    ) {}

    @GetMapping("/student/{id}")
    public ResponseEntity<List<StudentAttendanceRecord>> getByStudent(@PathVariable Long id) {
        List<Attendance> records = attendanceRepository.findByStudentId(id);
        List<StudentAttendanceRecord> result = records.stream()
                .map(a -> new StudentAttendanceRecord(a.getCalendarEventId(), a.getDate(), a.getStatus()))
                .toList();
        return ResponseEntity.ok(result);
    }

    public record StudentAttendanceRecord(
            String calendarEventId,
            LocalDate date,
            AttendanceStatus status
    ) {}
}
