package dev.healthintelligence.core.documents;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import dev.healthintelligence.core.shared.PatientId;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Live documents of one patient. Deleted ones (deleted_at set) are invisible to every query. */
interface DocumentRepository extends Repository<HealthDocument, UUID> {

    @Query("select d from HealthDocument d where d.id = :id and d.patientId = :patientId and d.deletedAt is null")
    Optional<HealthDocument> findLive(@Param("id") UUID id, @Param("patientId") PatientId patientId);

    @Query("""
            select count(d) > 0 from HealthDocument d
            where d.patientId = :patientId and d.sha256 = :sha256 and d.deletedAt is null""")
    boolean existsLiveWithHash(@Param("patientId") PatientId patientId, @Param("sha256") String sha256);

    /** Newest first, keyset pagination on (uploaded_at, id) (ADR 0009). */
    @Query("""
            select d from HealthDocument d
            where d.patientId = :patientId and d.deletedAt is null
              and (:status is null or d.status = :status)
              and (cast(:afterUploadedAt as Instant) is null
                   or d.uploadedAt < :afterUploadedAt
                   or (d.uploadedAt = :afterUploadedAt and d.id < :afterId))
            order by d.uploadedAt desc, d.id desc""")
    List<HealthDocument> page(@Param("patientId") PatientId patientId, @Param("status") DocumentStatus status,
                              @Param("afterUploadedAt") Instant afterUploadedAt, @Param("afterId") UUID afterId,
                              Limit limit);

    HealthDocument saveAndFlush(HealthDocument document);
}
