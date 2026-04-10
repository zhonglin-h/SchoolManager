package com.school.repository;

import com.school.entity.Attendance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AttendanceRepository extends JpaRepository<Attendance, Long> {
    List<Attendance> findByCalendarEventIdAndDate(String calendarEventId, LocalDate date);
    List<Attendance> findByStudentId(Long studentId);
    Optional<Attendance> findByStudentIdAndCalendarEventIdAndDate(Long studentId, String calendarEventId, LocalDate date);
}
