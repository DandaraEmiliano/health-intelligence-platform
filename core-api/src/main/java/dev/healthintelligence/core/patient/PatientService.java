package dev.healthintelligence.core.patient;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import dev.healthintelligence.core.audit.AuditLog;
import dev.healthintelligence.core.audit.AuditRecord;
import dev.healthintelligence.core.shared.ETags;
import dev.healthintelligence.core.shared.Ids;
import dev.healthintelligence.core.shared.PatientId;
import dev.healthintelligence.core.shared.ProblemException;
import dev.healthintelligence.core.shared.UserId;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PatientService {

    static final String NOT_FOUND = "PATIENT_NOT_FOUND";

    private final PatientRepository patients;
    private final AuditLog auditLog;
    private final Clock clock;

    PatientService(PatientRepository patients, AuditLog auditLog, Clock clock) {
        this.patients = patients;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    @Transactional
    Patient create(UserId owner, PatientProfile requested) {
        PatientProfile profile = requested.validated(today());
        if (patients.existsByUserId(owner)) {
            throw ProblemException.conflict("PATIENT_ALREADY_EXISTS");
        }
        Patient patient;
        try {
            patient = patients.saveAndFlush(new Patient(Ids.newId(), owner, profile));
        } catch (DataIntegrityViolationException concurrentlyCreated) {
            throw ProblemException.conflict("PATIENT_ALREADY_EXISTS");
        }
        auditLog.record(AuditRecord.userSuccess(owner.value(), "PATIENT_PROFILE_CREATED", "PATIENT",
                patient.id(), patient.id(), Map.of()));
        return patient;
    }

    @Transactional(readOnly = true)
    Patient get(UserId owner) {
        return patients.findByUserId(owner).orElseThrow(() -> ProblemException.notFound(NOT_FOUND));
    }

    @Transactional
    Patient update(UserId owner, String ifMatch, PatientPatch patch) {
        Patient patient = get(owner);
        ETags.requireMatch(ifMatch, patient.version());
        PatientProfile updated = patch.applyTo(patient.profile()).validated(today());
        if (updated.equals(patient.profile())) {
            return patient;
        }
        patient.apply(updated);
        patient = patients.saveAndFlush(patient);
        auditLog.record(AuditRecord.userSuccess(owner.value(), "PATIENT_PROFILE_UPDATED", "PATIENT",
                patient.id(), patient.id(), Map.of("fields", String.join(",", patch.fieldNames()))));
        return patient;
    }

    /** The caller's patient id, for other modules (via {@link CurrentPatient}). */
    @Transactional(readOnly = true)
    Optional<PatientId> findId(UserId owner) {
        return patients.findByUserId(owner).map(patient -> new PatientId(patient.id()));
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }
}
