package dev.healthintelligence.core.identity;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

interface UserAccountRepository extends Repository<UserAccount, UUID> {

    Optional<UserAccount> findByIdpSubject(String idpSubject);

    UserAccount saveAndFlush(UserAccount account);
}
