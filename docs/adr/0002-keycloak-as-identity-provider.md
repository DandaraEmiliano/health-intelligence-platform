# ADR 0002: Keycloak as identity provider

- **Status:** Proposed
- **Date:** 2026-11-12
- **Deciders:** Dandara Emiliano (owner), Claude (architect)

## Context

Requirements: patient registration and login, optional MFA, protection against credential attacks, a clean separation between authentication and the patient domain, service-to-service authentication between `core-api` and `ai-service`, and a future need for more roles (healthcare professionals, clinics, administrators). Health data raises the cost of any authentication flaw.

The local environment must run fully offline with Docker Compose. AWS deployment comes later (Phase 7).

## Decision

Use **Keycloak** as the OpenID Connect identity provider.

- Patients authenticate with **Authorization Code + PKCE**. `core-api` is an **OAuth 2.0 resource server**: it validates JWTs (issuer, audience, signature through JWKS, expiry) and never handles passwords.
- The `identity` module maps the token's `sub` to a local `UserAccount`. Domain modules depend on a `CurrentUser` abstraction, never on Keycloak classes.
- `core-api` calls `ai-service` with a **client-credentials** token (audience `ai-service`), validated by `ai-service`.
- Roles for the MVP: `PATIENT`, `ADMIN`. `ADMIN` is operational and has **no** access to clinical data by default.
- MFA (TOTP) is optional for patients and mandatory for `ADMIN`. Brute-force detection and the password policy are configured in the realm.
- The realm configuration is versioned in the repository (`infra/docker/keycloak/`) and imported at startup, containing synthetic users only.

## Alternatives considered

| Option | Pros | Cons |
| --- | --- | --- |
| Custom auth with Spring Security and self-issued JWTs | Full control; no extra container | We would own password storage, reset flows, MFA, lockout and token rotation: a large attack surface with no product value |
| Spring Authorization Server | Java-native, embeddable | Still leaves user management, MFA and an admin UI to build |
| Amazon Cognito | Managed; no servers to run | No faithful local emulation; ties local development to AWS; less flexible realm modelling |
| Auth0 / other SaaS | Managed, feature-rich | Cost, vendor lock-in, and another processor of personal data to cover under LGPD |

## Consequences

- One more container locally and one more service to operate in production (or a managed Keycloak). Cognito can be reassessed in Phase 7 without changing the domain, because the core only depends on standard OIDC/JWT validation.
- Authorization (who can see which patient) stays in `core-api`. Keycloak answers "who is this", never "may they see patient X".
- Integration tests use a Keycloak Testcontainer or a signed test token issuer; both must be supported by the security configuration.
- Account deletion must also delete the Keycloak user. This is part of the deletion workflow, not an afterthought.
