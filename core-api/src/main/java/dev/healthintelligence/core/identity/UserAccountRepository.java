package dev.healthintelligence.core.identity;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

interface UserAccountRepository extends Repository<UserAccount, UUID> {

    Optional<UserAccount> findByIdpSubject(String idpSubject);

    UserAccount saveAndFlush(UserAccount account);

    java.util.Optional<UserAccount> findById(UUID id);

    @org.springframework.data.jpa.repository.Query("""
            select a.id from UserAccount a
            where a.status = dev.healthintelligence.core.identity.AccountStatus.DELETION_REQUESTED
              and a.deletionRequestedAt <= :cutoff
            order by a.deletionRequestedAt""")
    java.util.List<UUID> findDueForPurge(@org.springframework.data.repository.query.Param("cutoff") java.time.Instant cutoff,
                                         org.springframework.data.domain.Limit limit);

    void delete(UserAccount account);
}
