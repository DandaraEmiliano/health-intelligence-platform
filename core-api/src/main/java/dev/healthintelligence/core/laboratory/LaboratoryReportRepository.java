package dev.healthintelligence.core.laboratory;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import dev.healthintelligence.core.shared.PatientId;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

interface LaboratoryReportRepository extends Repository<LaboratoryReport, UUID> {

    LaboratoryReport save(LaboratoryReport report);

    @Query("select r from LaboratoryReport r where r.id = :id and r.patientId = :patientId and r.status <> 'SUPERSEDED'")
    Optional<LaboratoryReport> findVisible(@Param("id") UUID id, @Param("patientId") PatientId patientId);

    /**
     * Most recent collection first; reports without a collection date last. Keyset on
     * (coalesce(collected_at, 0001-01-01), id) descending (ADR 0009).
     */
    @Query("""
            select r from LaboratoryReport r
            where r.patientId = :patientId and r.status <> 'SUPERSEDED'
              and (:status is null or r.status = :status)
              and (cast(:afterDate as LocalDate) is null
                   or coalesce(r.collectedAt, cast('0001-01-01' as LocalDate)) < :afterDate
                   or (coalesce(r.collectedAt, cast('0001-01-01' as LocalDate)) = :afterDate and r.id < :afterId))
            order by coalesce(r.collectedAt, cast('0001-01-01' as LocalDate)) desc, r.id desc""")
    List<LaboratoryReport> page(@Param("patientId") PatientId patientId, @Param("status") String status,
                                @Param("afterDate") LocalDate afterDate, @Param("afterId") UUID afterId, Limit limit);
}
