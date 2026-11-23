package dev.healthintelligence.core.assistant;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import dev.healthintelligence.core.shared.PatientId;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Every lookup takes the owner's {@link PatientId} as well as the conversation (ADR 0011). */
interface MessageRepository extends Repository<Message, UUID> {

    /** Oldest first, keyset pagination on (created_at, id). */
    @Query("""
            select m from Message m
            where m.conversationId = :conversationId and m.patientId = :patientId
              and (cast(:afterCreatedAt as Instant) is null
                   or m.createdAt > :afterCreatedAt
                   or (m.createdAt = :afterCreatedAt and m.id > :afterId))
            order by m.createdAt, m.id""")
    List<Message> page(@Param("conversationId") UUID conversationId, @Param("patientId") PatientId patientId,
                       @Param("afterCreatedAt") Instant afterCreatedAt, @Param("afterId") UUID afterId, Limit limit);

    /** Newest first; the caller reverses it for the history sent to ai-service. */
    @Query("""
            select m from Message m
            where m.conversationId = :conversationId and m.patientId = :patientId
            order by m.createdAt desc, m.id desc""")
    List<Message> latest(@Param("conversationId") UUID conversationId, @Param("patientId") PatientId patientId,
                         Limit limit);

    Message save(Message message);
}
