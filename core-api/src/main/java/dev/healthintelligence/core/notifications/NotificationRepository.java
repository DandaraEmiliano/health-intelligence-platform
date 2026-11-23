package dev.healthintelligence.core.notifications;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import dev.healthintelligence.core.shared.UserId;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Every lookup takes the owner's {@link UserId} (ADR 0011). */
interface NotificationRepository extends Repository<Notification, UUID> {

    @Query("select n from Notification n where n.id = :id and n.userId = :userId")
    Optional<Notification> findOwned(@Param("id") UUID id, @Param("userId") UserId userId);

    boolean existsByUserIdAndDedupeKey(UserId userId, String dedupeKey);

    /** Newest first, keyset pagination on (created_at, id). */
    @Query("""
            select n from Notification n
            where n.userId = :userId
              and (:status is null or n.status = :status)
              and (cast(:afterCreatedAt as Instant) is null
                   or n.createdAt < :afterCreatedAt
                   or (n.createdAt = :afterCreatedAt and n.id < :afterId))
            order by n.createdAt desc, n.id desc""")
    List<Notification> page(@Param("userId") UserId userId, @Param("status") Notification.Status status,
                            @Param("afterCreatedAt") Instant afterCreatedAt, @Param("afterId") UUID afterId,
                            Limit limit);

    Notification save(Notification notification);
}
