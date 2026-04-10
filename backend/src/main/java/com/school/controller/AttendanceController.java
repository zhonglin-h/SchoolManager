package com.school.controller;

import com.school.dto.AttendanceSummaryResponse;
import com.school.entity.Attendance;
import com.school.entity.AttendanceStatus;
import com.school.model.CalendarEvent;
import com.school.repository.AttendanceRepository;
import com.school.repository.StudentRepository;
import com.school.service.CalendarSyncService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
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
@CrossOrigin(origins = "http://localhost:3000")
public class AttendanceController {

    private final CalendarSyncService calendarSyncService;
    private final AttendanceRepository attendanceRepository;
    private final StudentRepository studentRepository;

    public AttendanceController(CalendarSyncService calendarSyncService,
                                 AttendanceRepository attendanceRepository,
                                 StudentRepository studentRepository) {
        this.calendarSyncService = calendarSyncService;
        this.attendanceRepository = attendanceRepository;
        this.studentRepository = studentRepository;
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
                    .collect(Collectors.toMap(a -> a.getStudent().getId(), Function.identity()));

            List<AttendanceSummaryResponse.StudentAttendanceEntry> entries = new ArrayList<>();
            int present = 0, late = 0, absent = 0;

            if (event.getAttendeeEmails() != null) {
                for (String email : event.getAttendeeEmails()) {
                    studentRepository.findByMeetEmail(email).ifPresent(student -> {
                        Attendance att = byStudentId.get(student.getId());
                        AttendanceStatus status = att != null ? att.getStatus() : null;
                        entries.add(new AttendanceSummaryResponse.StudentAttendanceEntry(
                                student.getId(), student.getName(), status));
                    });
                }
            }

            for (AttendanceSummaryResponse.StudentAttendanceEntry entry : entries) {
                if (entry.status() == AttendanceStatus.PRESENT) present++;
                else if (entry.status() == AttendanceStatus.LATE) late++;
                else if (entry.status() == AttendanceStatus.ABSENT) absent++;
            }

            summaries.add(new AttendanceSummaryResponse(
                    event.getId(),
                    event.getTitle(),
                    today,
                    event.getStartTime().toLocalTime(),
                    event.getEndTime().toLocalTime(),
                    entries.size(),
                    present,
                    late,
                    absent,
                    entries
            ));
        }

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
