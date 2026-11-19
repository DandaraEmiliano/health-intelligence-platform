package dev.healthintelligence.core.shared;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

interface OutboxEventRepository extends Repository<OutboxEvent, UUID> {

    OutboxEvent save(OutboxEvent event);

    /**
     * Oldest unpublished events, locked so that several core-api instances never publish the same
     * row at once (SKIP LOCKED lets each instance take a different batch).
     */
    @NativeQuery("""
            select * from core.outbox_event
            where published_at is null and attempts < :maxAttempts
            order by created_at, id
            limit :limit
            for update skip locked
            """)
    List<OutboxEvent> lockPending(@Param("limit") int limit, @Param("maxAttempts") int maxAttempts);
}
