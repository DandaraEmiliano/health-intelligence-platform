package dev.healthintelligence.core.assistant;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import dev.healthintelligence.core.shared.PatientId;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Every lookup takes the owner's {@link PatientId} (ADR 0011). */
interface ConversationRepository extends Repository<Conversation, UUID> {

    @Query("select c from Conversation c where c.id = :id and c.patientId = :patientId")
    Optional<Conversation> findOwned(@Param("id") UUID id, @Param("patientId") PatientId patientId);

    /** Most recently updated first, keyset pagination on (updated_at, id) (ADR 0009). */
    @Query("""
            select c from Conversation c
            where c.patientId = :patientId
              and (cast(:afterUpdatedAt as Instant) is null
                   or c.updatedAt < :afterUpdatedAt
                   or (c.updatedAt = :afterUpdatedAt and c.id < :afterId))
            order by c.updatedAt desc, c.id desc""")
    List<Conversation> page(@Param("patientId") PatientId patientId, @Param("afterUpdatedAt") Instant afterUpdatedAt,
                            @Param("afterId") UUID afterId, Limit limit);

    Conversation saveAndFlush(Conversation conversation);

    void delete(Conversation conversation);
}
