# ADR 0012: Open-source local emulators for S3 and SQS

- **Status:** Proposed
- **Date:** 2026-11-12
- **Deciders:** Dandara Emiliano (owner), Claude (architect)
- **Amends:** ADR 0003 ("Locally, SQS and S3 are provided by LocalStack")

## Context

ADR 0003 planned LocalStack for local S3 and SQS. Since March 2026 the `localstack/localstack` image requires an account and an auth token to start, including in CI. The free tier is for non-commercial use and has no CI credits. Requiring every contributor and every CI run to hold a vendor token adds friction and an external dependency to a local environment that must run offline (NFR-PORT-01).

## Decision

Use **single-purpose, open-source emulators** that need no account:

| AWS service | Emulator | Image (pinned) | Port |
| --- | --- | --- | --- |
| S3 | Adobe S3Mock (Apache-2.0) | `adobe/s3mock:5.2.3` | 9090 |
| SQS | ElasticMQ (Apache-2.0) | `softwaremill/elasticmq-native:1.7.1` | 9324 |

- Application code uses the official AWS SDKs with an endpoint override (`AWS_ENDPOINT_URL_S3`, `AWS_ENDPOINT_URL_SQS`) only in local and test profiles. No emulator-specific code.
- Queues and dead-letter queues are declared in `infra/docker/elasticmq/elasticmq.conf`; the bucket is created by S3Mock at startup.
- Neither image ships an HTTP client for container health checks, so `infra/docker/smoke-test.sh` verifies both through the AWS CLI. CI runs it.
- Behaviour that emulators do not reproduce faithfully (IAM policies, KMS encryption, exact SQS redrive semantics) is verified in AWS in Phase 7, not assumed from local runs.

## Alternatives considered

| Option | Pros | Cons |
| --- | --- | --- |
| LocalStack with a free auth token | One container, broad API coverage | Account and token for every developer and CI; non-commercial terms; external dependency |
| Floci / other new drop-in replacements | LocalStack-compatible, no token | Very young projects (2026); maturity and maintenance unproven |
| MinIO for S3 | Mature S3 implementation | Community edition distribution changed in 2025; heavier than needed for tests |
| Real AWS for local development | Exact behaviour | Cost, credentials on laptops, no offline work |

## Consequences

- Two small containers instead of one. Both start in seconds and need no credentials.
- Feature gaps between emulators and AWS are a known risk; integration tests against real AWS are planned in Phase 7.
- Testcontainers modules for S3Mock and ElasticMQ (generic containers) replace the LocalStack module in later phases.
