package dev.healthintelligence.core.patient;

import java.util.Optional;
import java.util.UUID;

import dev.healthintelligence.core.shared.UserId;
import org.springframework.data.repository.Repository;

/**
 * No {@code findById}: the profile is always reached through the owner's user id, which comes from
 * the token (ADR 0011).
 */
interface PatientRepository extends Repository<Patient, UUID> {

    Optional<Patient> findByUserId(UserId userId);

    boolean existsByUserId(UserId userId);

    Patient saveAndFlush(Patient patient);
}
