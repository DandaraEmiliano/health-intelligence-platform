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

    @Query("select r from LaboratoryResult r where r.reportId in :reportIds and r.patientId = :patientId order by r.sequence")
    List<LaboratoryResult> findByReports(@Param("reportIds") Collection<UUID> reportIds,
                                         @Param("patientId") PatientId patientId);
}
