package dev.healthintelligence.core.documents;

import java.util.UUID;

import org.springframework.data.repository.Repository;

interface ExtractionJobRepository extends Repository<ExtractionJob, UUID> {

    ExtractionJob save(ExtractionJob job);

    /**
     * The job a result message names, if it belongs to that patient. Locked, so two deliveries of
     * the same result cannot both complete it.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select j from ExtractionJob j where j.id = :id and j.patientId = :patientId")
    java.util.Optional<ExtractionJob> findForUpdate(@org.springframework.data.repository.query.Param("id") UUID id,
            @org.springframework.data.repository.query.Param("patientId") dev.healthintelligence.core.shared.PatientId patientId);
}
