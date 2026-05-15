package com.school.service.meet;

import com.school.entity.Person;
import com.school.entity.PersonType;
import com.school.integration.MeetParticipant;
import com.school.model.CalendarEvent;
import com.school.repository.PersonRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
class MeetParticipantResolver {

    private final PersonRepository personRepository;

    MeetParticipantResolver(PersonRepository personRepository) {
        this.personRepository = personRepository;
    }

    /**
     * Package API: resolves live Meet participants to active students/teachers and
     * opportunistically stores missing Meet identity fields on matched people.
     */
    ResolvedParticipants resolveAndAutoLearn(List<MeetParticipant> participants) {
        Set<Long> studentIds = new HashSet<>();
        Set<Long> teacherIds = new HashSet<>();
        Map<Long, Instant> joinTimes = new HashMap<>();

        for (MeetParticipant participant : participants) {
            Optional<Person> student = resolveAndAutoLearnForType(participant, PersonType.STUDENT);
            if (student.isPresent()) {
                studentIds.add(student.get().getId());
                if (participant.earliestStartTime() != null) {
                    joinTimes.put(student.get().getId(), participant.earliestStartTime());
                }
                continue;
            }

            Optional<Person> teacher = resolveAndAutoLearnForType(participant, PersonType.TEACHER);
            if (teacher.isPresent()) {
                teacherIds.add(teacher.get().getId());
                if (participant.earliestStartTime() != null) {
                    joinTimes.put(teacher.get().getId(), participant.earliestStartTime());
                }
            }
        }
        return new ResolvedParticipants(studentIds, teacherIds, joinTimes);
    }

    private Optional<Person> resolveAndAutoLearnForType(MeetParticipant participant, PersonType personType) {
        Optional<Person> resolved = resolvePersonFromDb(participant, personType);
        resolved.ifPresent(person -> {
            if (autoLearnMeetIdentity(participant, person)) {
                personRepository.save(person);
            }
        });
        return resolved;
    }

    private Optional<Person> resolvePersonFromDb(MeetParticipant participant, PersonType personType) {
        if (participant.googleUserId() != null) {
            Optional<Person> byUserId = personRepository
                    .findByPersonTypeAndGoogleUserIdAndActiveTrue(personType, participant.googleUserId());
            if (byUserId.isPresent()) {
                return byUserId;
            }
        }
        if (participant.displayName() != null) {
            return personRepository.findByPersonTypeAndMeetDisplayNameIgnoreCaseAndActiveTrue(
                            personType, participant.displayName())
                    .or(() -> personRepository.findByPersonTypeAndNameIgnoreCaseAndActiveTrue(
                            personType, participant.displayName()));
        }
        return Optional.empty();
    }

    private boolean autoLearnMeetIdentity(MeetParticipant participant, Person person) {
        boolean changed = false;
        if (participant.googleUserId() != null && person.getGoogleUserId() == null) {
            person.setGoogleUserId(participant.googleUserId());
            changed = true;
        }
        if (participant.displayName() != null && person.getMeetDisplayName() == null) {
            person.setMeetDisplayName(participant.displayName());
            changed = true;
        }
        if (changed) {
            log.info("Auto-learned Meet identity for {}: userId={}, displayName={}",
                    person.getPersonType().name().toLowerCase() + " " + person.getName(),
                    person.getGoogleUserId(), person.getMeetDisplayName());
        }
        return changed;
    }

    /**
     * Package API: returns attendee emails from the event that do not map to any
     * active person by meet email.
     */
    List<String> findUnmatchedInvitees(CalendarEvent event) {
        List<String> attendeeEmails = attendeeEmails(event);
        if (attendeeEmails.isEmpty()) {
            return List.of();
        }
        Map<String, Person> peopleByEmail = findActivePeopleByMeetEmail(attendeeEmails);
        List<String> unmatched = new ArrayList<>();
        for (String email : attendeeEmails) {
            if (!peopleByEmail.containsKey(normalizeEmail(email))) {
                unmatched.add(email);
            }
        }
        return unmatched;
    }

    /**
     * Package API: convenience view over unknown-participant classification,
     * flattened to display names for notifications.
     */
    List<String> findUnmatchedParticipants(List<MeetParticipant> participants, ExpectedParticipants expected) {
        return classifyUnknownParticipants(participants, expected).flattenedDisplayNames();
    }

    /**
     * Package API: classifies participants that are not expected attendees into:
     * people known in system vs people not found in system.
     */
    UnknownParticipantClassification classifyUnknownParticipants(List<MeetParticipant> participants, ExpectedParticipants expected) {
        Map<String, UnknownParticipantEntry> knownButUnexpectedByKey = new LinkedHashMap<>();
        Map<String, UnknownParticipantEntry> notInSystemByKey = new LinkedHashMap<>();
        for (MeetParticipant participant : participants) {
            ParticipantIdentity identity = participantIdentity(participant);
            if (identity.key() == null || isExpectedParticipant(participant, expected)) {
                continue;
            }
            UnknownParticipantEntry entry = new UnknownParticipantEntry(identity.key(), identity.label());
            if (resolveAnyActivePerson(participant).isPresent()) {
                knownButUnexpectedByKey.putIfAbsent(identity.key(), entry);
            } else {
                notInSystemByKey.putIfAbsent(identity.key(), entry);
            }
        }
        return new UnknownParticipantClassification(
                new ArrayList<>(knownButUnexpectedByKey.values()),
                new ArrayList<>(notInSystemByKey.values()));
    }

    private boolean matchesExpectedPerson(MeetParticipant participant, Person person) {
        if (participant.googleUserId() != null && participant.googleUserId().equals(person.getGoogleUserId())) {
            return true;
        }
        if (participant.displayName() == null) {
            return false;
        }
        if (participant.displayName().equalsIgnoreCase(person.getMeetDisplayName())) {
            return true;
        }
        return participant.displayName().equalsIgnoreCase(person.getName());
    }

    private boolean isExpectedParticipant(MeetParticipant participant, ExpectedParticipants expected) {
        for (Person student : expected.students()) {
            if (matchesExpectedPerson(participant, student)) {
                return true;
            }
        }
        for (Person teacher : expected.teachers()) {
            if (matchesExpectedPerson(participant, teacher)) {
                return true;
            }
        }
        return false;
    }

    private Optional<Person> resolveAnyActivePerson(MeetParticipant participant) {
        Optional<Person> student = resolvePersonFromDb(participant, PersonType.STUDENT);
        if (student.isPresent()) {
            return student;
        }
        return resolvePersonFromDb(participant, PersonType.TEACHER);
    }

    private ParticipantIdentity participantIdentity(MeetParticipant participant) {
        String key = null;
        if (participant.googleUserId() != null && !participant.googleUserId().isBlank()) {
            key = "uid:" + participant.googleUserId().trim().toLowerCase();
        } else if (participant.displayName() != null && !participant.displayName().isBlank()) {
            key = "name:" + participant.displayName().trim().toLowerCase();
        }

        if (participant.displayName() != null && !participant.displayName().isBlank()) {
            return new ParticipantIdentity(key, participant.displayName());
        }
        if (participant.googleUserId() != null && !participant.googleUserId().isBlank()) {
            return new ParticipantIdentity(key, participant.googleUserId());
        }
        return new ParticipantIdentity(key, "(unknown participant)");
    }

    /**
     * Package API: resolves expected students/teachers from event attendees,
     * preserving attendee order for downstream messaging.
     */
    ExpectedParticipants getExpectedParticipants(CalendarEvent event) {
        List<String> attendeeEmails = attendeeEmails(event);
        List<Person> students = new ArrayList<>();
        List<Person> teachers = new ArrayList<>();
        if (attendeeEmails.isEmpty()) {
            return new ExpectedParticipants(students, teachers);
        }
        Map<String, Person> peopleByEmail = findActivePeopleByMeetEmail(attendeeEmails);
        for (String email : attendeeEmails) {
            Person person = peopleByEmail.get(normalizeEmail(email));
            if (person == null) {
                continue;
            }
            if (person.getPersonType() == PersonType.STUDENT) {
                students.add(person);
            } else if (person.getPersonType() == PersonType.TEACHER) {
                teachers.add(person);
            }
        }
        return new ExpectedParticipants(students, teachers);
    }

    private List<String> attendeeEmails(CalendarEvent event) {
        if (event.getAttendeeEmails() == null) {
            return List.of();
        }
        List<String> emails = new ArrayList<>();
        for (String email : event.getAttendeeEmails()) {
            if (email != null && !email.isBlank()) {
                emails.add(email);
            }
        }
        return emails;
    }

    private Map<String, Person> findActivePeopleByMeetEmail(List<String> attendeeEmails) {
        Map<String, Person> byEmail = new HashMap<>();
        if (attendeeEmails.isEmpty()) {
            return byEmail;
        }
        List<Person> people = personRepository.findByMeetEmailInAndActiveTrue(attendeeEmails);
        for (Person person : people) {
            if (person.getMeetEmail() == null || person.getMeetEmail().isBlank()) {
                continue;
            }
            byEmail.put(normalizeEmail(person.getMeetEmail()), person);
        }
        return byEmail;
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase();
    }

    /**
     * Package API: resolves a best-effort join timestamp for a person using
     * user id first, then Meet display name, then canonical person name.
     */
    Instant resolveJoinTime(String googleUserId, String meetDisplayName, String name,
                            Map<String, Instant> byUserId, Map<String, Instant> byDisplayName) {
        if (googleUserId != null && byUserId.containsKey(googleUserId)) {
            return byUserId.get(googleUserId);
        }
        if (meetDisplayName != null && byDisplayName.containsKey(meetDisplayName.toLowerCase())) {
            return byDisplayName.get(meetDisplayName.toLowerCase());
        }
        if (name != null && byDisplayName.containsKey(name.toLowerCase())) {
            return byDisplayName.get(name.toLowerCase());
        }
        return null;
    }
}

record ResolvedParticipants(Set<Long> studentIds, Set<Long> teacherIds, Map<Long, Instant> joinTimes) {}
record ExpectedParticipants(List<Person> students, List<Person> teachers) {}
record UnknownParticipantEntry(String key, String displayName) {}
record ParticipantIdentity(String key, String label) {}
record UnknownParticipantClassification(
        List<UnknownParticipantEntry> knownButUnexpected,
        List<UnknownParticipantEntry> notInSystem
) {
    List<String> flattenedDisplayNames() {
        List<String> names = new ArrayList<>();
        knownButUnexpected.forEach(entry -> names.add(entry.displayName()));
        notInSystem.forEach(entry -> names.add(entry.displayName()));
        return names;
    }
}
