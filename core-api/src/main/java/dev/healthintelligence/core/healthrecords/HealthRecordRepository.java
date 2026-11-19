package dev.healthintelligence.core.healthrecords;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import dev.healthintelligence.core.shared.PatientId;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;

/** Every lookup takes the owner's {@link PatientId}; there is no lookup by id alone (ADR 0011). */
@NoRepositoryBean
interface HealthRecordRepository<T extends HealthRecord<?>> extends Repository<T, UUID> {

    Optional<T> findByIdAndPatientId(UUID id, PatientId patientId);

    List<T> findByPatientIdOrderByCreatedAtAscIdAsc(PatientId patientId);

    long countByPatientId(PatientId patientId);

    T saveAndFlush(T record);

    void delete(T record);
}
