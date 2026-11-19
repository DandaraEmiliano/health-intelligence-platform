package dev.healthintelligence.core.laboratory;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import dev.healthintelligence.core.shared.PatientId;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

interface LaboratoryResultRepository extends Repository<LaboratoryResult, UUID> {

    LaboratoryResult save(LaboratoryResult result);

    /**
     * Results the patient kept (VERIFIED or CORRECTED) from reports whose review is finished, the
     * only data timeline, analytics and the assistant may use (ADR 0006). Oldest first.
     */
    @Query("""
            select r from LaboratoryResult r, LaboratoryReport rep
            where rep.id = r.reportId and rep.patientId = :patientId and r.patientId = :patientId
              and rep.status = 'VERIFIED' and r.reviewStatus in ('VERIFIED', 'CORRECTED')
              and (:analyteId is null or r.analyteId = :analyteId)
              and (cast(:fromDate as LocalDate) is null or r.collectedAt >= :fromDate)
              and (cast(:toDate as LocalDate) is null or r.collectedAt <= :toDate)
            order by r.collectedAt, r.sequence, r.id""")
    List<LaboratoryResult> findVerified(@Param("patientId") PatientId patientId, @Param("analyteId") UUID analyteId,
                                        @Param("fromDate") java.time.LocalDate fromDate,
                                        @Param("toDate") java.time.LocalDate toDate);

    @Query("select r from LaboratoryResult r where r.reportId in :reportIds and r.patientId = :patientId order by r.sequence")
    List<LaboratoryResult> findByReports(@Param("reportIds") Collection<UUID> reportIds,
                                         @Param("patientId") PatientId patientId);
}
