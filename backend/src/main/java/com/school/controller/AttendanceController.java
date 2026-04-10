package com.school.controller;

import com.school.dto.AttendanceSummaryResponse;
import com.school.entity.Attendance;
import com.school.entity.AttendanceStatus;
import com.school.integration.MeetClient;
import com.school.model.CalendarEvent;
import com.school.repository.AttendanceRepository;
import com.school.repository.StudentRepository;
import com.school.repository.TeacherRepository;
import com.school.service.CalendarSyncService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/attendance")
public class AttendanceController {

    private final CalendarSyncService calendarSyncService;
    private final AttendanceRepository attendanceRepository;
    private final StudentRepository studentRepository;
    private final TeacherRepository teacherRepository;
    private final MeetClient meetClient;
    private final String principalEmail;

    public AttendanceController(CalendarSyncService calendarSyncService,
                                AttendanceRepository attendanceRepository,
                                StudentRepository studentRepository,
                                TeacherRepository teacherRepository,
                                MeetClient meetClient,
                                @Value("${app.principal.email}") String principalEmail) {
        this.calendarSyncService = calendarSyncService;
        this.attendanceRepository = attendanceRepository;
        this.studentRepository = studentRepository;
        this.teacherRepository = teacherRepository;
        this.meetClient = meetClient;
        this.principalEmail = principalEmail;
    }

    @GetMapping("/today")
    public ResponseEntity<List<AttendanceSummaryResponse>> getToday() throws Exception {
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
            try {
                meetingActive = meetClient.isMeetingActive(event.getSpaceCode());
            } catch (Exception ignored) {}

            List<AttendanceSummaryResponse.AttendanceEntry> entries = new ArrayList<>();
            int present = 0, late = 0, absent = 0;

            if (event.getAttendeeEmails() != null) {
                for (String email : event.getAttendeeEmails()) {
                    if (email.equalsIgnoreCase(principalEmail)) continue;
                    var studentOpt = studentRepository.findByMeetEmail(email);
                    if (studentOpt.isPresent()) {
                        var student = studentOpt.get();
                        Attendance att = byStudentId.get(student.getId());
                        AttendanceStatus status = att != null ? att.getStatus() : null;
                        entries.add(new AttendanceSummaryResponse.AttendanceEntry(
                                student.getId(), "STUDENT", student.getName(), email, status, true));
                    } else {
                        var teacherOpt = teacherRepository.findByMeetEmail(email);
                        if (teacherOpt.isPresent()) {
                            var teacher = teacherOpt.get();
                            Attendance att = byTeacherId.get(teacher.getId());
                            AttendanceStatus status = att != null ? att.getStatus() : null;
                            entries.add(new AttendanceSummaryResponse.AttendanceEntry(
                                    teacher.getId(), "TEACHER", teacher.getName(), email, status, true));
                        } else {
                            entries.add(new AttendanceSummaryResponse.AttendanceEntry(
                                    null, null, email, email, null, false));
                        }
                    }
                }
            }

            for (AttendanceSummaryResponse.AttendanceEntry entry : entries) {
                if (entry.status() == AttendanceStatus.PRESENT || entry.status() == AttendanceStatus.LATE) present++;
                else if (entry.status() == AttendanceStatus.ABSENT) absent++;
            }

            int totalExpected = entries.size();

            summaries.add(new AttendanceSummaryResponse(
                    event.getId(),
                    event.getTitle(),
                    today,
                    event.getStartTime().toLocalTime(),
                    event.getEndTime().toLocalTime(),
                    meetingActive,
                    totalExpected,
                    present,
                    late,
                    absent,
                    entries
            ));
        }

        summaries.sort(java.util.Comparator
                .comparing(AttendanceSummaryResponse::startTime)
                .thenComparing(AttendanceSummaryResponse::eventTitle));

        return ResponseEntity.ok(summaries);
    }

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
