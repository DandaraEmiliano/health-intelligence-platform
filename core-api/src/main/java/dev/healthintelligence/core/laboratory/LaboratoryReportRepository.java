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

    @Query("select r from LaboratoryReport r where r.id in :ids and r.patientId = :patientId")
    List<LaboratoryReport> findByIds(@Param("ids") java.util.Collection<UUID> ids, @Param("patientId") PatientId patientId);

    /** Finished reviews for the timeline, newest collection first, keyset on (collected_at, id). */
    @Query("""
            select r from LaboratoryReport r
            where r.patientId = :patientId and r.status = 'VERIFIED' and r.collectedAt is not null
              and (cast(:afterDate as LocalDate) is null or r.collectedAt < :afterDate
                   or (r.collectedAt = :afterDate and r.id < :afterId))
              and (cast(:fromDate as LocalDate) is null or r.collectedAt >= :fromDate)
              and (cast(:toDate as LocalDate) is null or r.collectedAt <= :toDate)
            order by r.collectedAt desc, r.id desc""")
    List<LaboratoryReport> verifiedPage(@Param("patientId") PatientId patientId, @Param("afterDate") LocalDate afterDate,
                                        @Param("afterId") UUID afterId, @Param("fromDate") LocalDate fromDate,
                                        @Param("toDate") LocalDate toDate, Limit limit);

    @Query("select r from LaboratoryReport r where r.id = :id and r.patientId = :patientId and r.status <> 'SUPERSEDED'")
    Optional<LaboratoryReport> findVisible(@Param("id") UUID id, @Param("patientId") PatientId patientId);

    /** For review: every review changes the report's version, so its ETag guards all of its results. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.OPTIMISTIC_FORCE_INCREMENT)
    @Query("select r from LaboratoryReport r where r.id = :id and r.patientId = :patientId and r.status <> 'SUPERSEDED'")
    Optional<LaboratoryReport> findForReview(@Param("id") UUID id, @Param("patientId") PatientId patientId);

    /** Reports the patient still has to review, for the home page. */
    @Query("select count(r) from LaboratoryReport r where r.patientId = :patientId and r.status <> 'SUPERSEDED'")
    long countVisible(@Param("patientId") PatientId patientId);

    /** Every report the patient can see, oldest first, for the data export. */
    @Query("select r from LaboratoryReport r where r.patientId = :patientId and r.status <> 'SUPERSEDED' order by r.collectedAt nulls first, r.id")
    List<LaboratoryReport> findAllVisible(@Param("patientId") PatientId patientId);

    @Query("select count(r) from LaboratoryReport r where r.patientId = :patientId and r.status = 'PENDING_REVIEW'")
    long countPendingReview(@Param("patientId") PatientId patientId);

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
