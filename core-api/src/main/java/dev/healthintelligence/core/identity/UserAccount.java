package dev.healthintelligence.core.identity;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Local account mapped to the identity provider subject (ADR 0002). Holds no credentials. */
@Entity
@Table(name = "user_account")
class UserAccount {

    @Id
    private UUID id;

    @Column(name = "idp_subject", nullable = false, updatable = false)
    private String idpSubject;

    @Column(nullable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_role", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private Set<Role> roles = EnumSet.noneOf(Role.class);

    @Version
    private Long version;

    protected UserAccount() {
    }

    UserAccount(UUID id, String idpSubject, String email, Set<Role> roles) {
        this.id = id;
        this.idpSubject = idpSubject;
        this.email = email;
        this.status = AccountStatus.ACTIVE;
        this.roles = roles.isEmpty() ? EnumSet.noneOf(Role.class) : EnumSet.copyOf(roles);
    }

    /** Keeps email and roles as the identity provider states them. Returns true if anything changed. */
    boolean syncWith(String tokenEmail, Set<Role> tokenRoles) {
        boolean changed = false;
        if (!email.equals(tokenEmail)) {
            email = tokenEmail;
            changed = true;
        }
        if (!roles.equals(tokenRoles)) {
            roles.clear();
            roles.addAll(tokenRoles);
            changed = true;
        }
        return changed;
    }

    UUID id() {
        return id;
    }

    AccountStatus status() {
        return status;
    }
}
