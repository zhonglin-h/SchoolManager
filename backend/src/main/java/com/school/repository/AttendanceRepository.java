package com.school.repository;

import com.school.entity.Attendance;
import com.school.entity.PersonType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AttendanceRepository extends JpaRepository<Attendance, Long> {
    List<Attendance> findByCalendarEventIdAndDate(String calendarEventId, LocalDate date);
    List<Attendance> findByPersonId(Long personId);
    Optional<Attendance> findByPersonIdAndCalendarEventIdAndDate(Long personId, String calendarEventId, LocalDate date);

    @Query("SELECT a FROM Attendance a WHERE a.person.personType = :personType ORDER BY a.date DESC, a.id DESC")
    List<Attendance> findByPersonTypeOrderByDateDescIdDesc(@Param("personType") PersonType personType);

    /** Returns all records (both student and teacher), ordered by date desc. */
    @Query("SELECT a FROM Attendance a ORDER BY a.date DESC, a.id DESC")
    List<Attendance> findAllOrderByDateDescIdDesc();

    /** Returns records for a specific person id, ordered by date desc. */
    List<Attendance> findByPersonIdOrderByDateDescIdDesc(Long personId);
}
