package com.school.repository;

import com.school.entity.Attendance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AttendanceRepository extends JpaRepository<Attendance, Long> {
    List<Attendance> findByCalendarEventIdAndDate(String calendarEventId, LocalDate date);
    List<Attendance> findByStudentId(Long studentId);
    Optional<Attendance> findByStudentIdAndCalendarEventIdAndDate(Long studentId, String calendarEventId, LocalDate date);
    List<Attendance> findByTeacherId(Long teacherId);
    Optional<Attendance> findByTeacherIdAndCalendarEventIdAndDate(Long teacherId, String calendarEventId, LocalDate date);

    /** Returns all student records, ordered by date desc. */
    List<Attendance> findByStudentNotNullOrderByDateDescIdDesc();

    /** Returns all teacher records, ordered by date desc. */
    List<Attendance> findByTeacherNotNullOrderByDateDescIdDesc();

    /** Returns all records (both student and teacher), ordered by date desc. */
    @Query("SELECT a FROM Attendance a ORDER BY a.date DESC, a.id DESC")
    List<Attendance> findAllOrderByDateDescIdDesc();

    /** Returns student records for a specific student id, ordered by date desc. */
    List<Attendance> findByStudentIdOrderByDateDescIdDesc(Long studentId);

    /** Returns teacher records for a specific teacher id, ordered by date desc. */
    List<Attendance> findByTeacherIdOrderByDateDescIdDesc(Long teacherId);
}
