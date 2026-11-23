package dev.healthintelligence.core.patient;

import java.util.Optional;
import java.util.UUID;

import dev.healthintelligence.core.shared.PatientId;
import dev.healthintelligence.core.shared.UserId;
import org.springframework.data.repository.Repository;

/**
 * No {@code findById}: the profile is always reached through the owner's user id, which comes from
 * the token (ADR 0011).
 */
interface PatientRepository extends Repository<Patient, UUID> {

    Optional<Patient> findByUserId(UserId userId);

    boolean existsByUserId(UserId userId);

    /** The profile of a patient id that came from our own data (an event), never from a request. */
    @org.springframework.data.jpa.repository.Query("select p from Patient p where p.id = :#{#patient.value()}")
    Optional<Patient> findByPatientId(@org.springframework.data.repository.query.Param("patient") PatientId patient);

    Patient saveAndFlush(Patient patient);
}
