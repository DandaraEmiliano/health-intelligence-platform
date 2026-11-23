package dev.healthintelligence.core.consent;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Insert and read only: consent history is never rewritten. */
interface ConsentRecordRepository extends Repository<ConsentRecord, UUID> {

    ConsentRecord save(ConsentRecord record);

    /** The whole history, oldest first, for the data export. */
    List<ConsentRecord> findByUserIdOrderByRecordedAtAscIdAsc(UUID userId);

    /** Latest record of each type for the user (ix_consent_record_current). Ids are time-ordered. */
    @NativeQuery("""
            select distinct on (consent_type) *
            from core.consent_record
            where user_id = :userId
            order by consent_type, recorded_at desc, id desc
            """)
    List<ConsentRecord> findCurrent(@Param("userId") UUID userId);
}
