# AWS infrastructure (Terraform)

Written and checked in CI, **never applied yet**. Applying it creates billable resources and holds health data, so it waits for an explicit decision. Everything below is what `terraform apply` would create in `sa-east-1` (São Paulo), so the data stays in Brazil.

| Module | What it creates |
| --- | --- |
| [`network`](modules/network) | VPC with three tiers per zone: public (load balancer, NAT), app (ECS tasks), data (RDS, Redis). The data tier has no route out. S3 gateway endpoint, flow logs of rejected traffic, deny-all default security group |
| [`data`](modules/data) | Customer-managed KMS key (rotation on). PostgreSQL 16 on RDS: encrypted, TLS only, master password managed by RDS in Secrets Manager, deletion protection, final snapshot. Redis (ElastiCache) with TLS and an auth token. Documents bucket: private, versioned, SSE-KMS, TLS-only policy, old versions expire after 7 days. SQS request and result queues, each with a dead-letter queue. Secrets Manager entries for the app credentials |
| [`services`](modules/services) | ECR (immutable tags, scan on push). ECS Fargate (ARM64) for core-api, ai-service and web: read-only root file systems, non-root users, all capabilities dropped. One HTTPS load balancer (TLS 1.2+); `/api/*` goes to core-api, everything else to web; actuator and API docs are never public. ai-service is reachable only from core-api through private DNS. One least-privilege IAM role per service |
| [`environments/staging`](environments/staging) | The three modules with smaller settings (one NAT gateway, single-AZ database, one task per service) |

Security groups allow only: internet → load balancer :443, load balancer → web and core-api :8080, core-api → ai-service :8081, tasks → PostgreSQL and Redis, and HTTPS out. The accepted findings are annotated in place with `#trivy:ignore`, and every other high or critical finding fails CI.

## Checks (CI: `.github/workflows/infra.yml`)

```bash
cd infra/terraform/environments/staging
terraform init -backend=false && terraform validate
terraform fmt -check -recursive ../..
docker run --rm -v "$PWD/../..:/src:ro" aquasec/trivy:0.67.2 config --severity HIGH,CRITICAL --exit-code 1 /src
```

The same workflow also builds the three images on every change. It checks that they do not run as root and scans them for fixable high or critical vulnerabilities.

## Before the first apply

These need decisions or accounts that engineering cannot settle alone:

1. **AWS account and state.** Create the S3 state bucket, then `terraform init -backend-config=backend.hcl` (see `backend.hcl.example`; `use_lockfile` needs Terraform 1.10+).
2. **Identity provider.** Keycloak is not provisioned here (ADR 0002 leaves Keycloak-on-ECS vs. Cognito open). The variables take its issuer URL.
3. **Domain and certificate.** An ACM certificate for `domain_name`, and DNS pointing at the load balancer.
4. **Secrets.** Set the values of the secrets created by `data` (database roles, OIDC client secrets, the LLM API key) out of band, never through Terraform.
5. **Database bootstrap.** Create the `core_owner`, `core_app`, `ai_owner` and `ai_app` roles and the `core` and `ai` schemas, as `infra/docker/postgres/init` does locally, as a one-off task using the RDS master secret. Then enable `pgvector`.
6. **Database TLS.** core-api connects with `sslmode=require`. Bundle the RDS CA in the image and switch to `verify-full`.
7. **Legal.** International transfer to the LLM provider and the retention periods (see `docs/security/privacy-requirements-map.md`).

## Delivery (`.github/workflows/deploy.yml`)

The workflow builds ARM64 images tagged with the commit SHA, pushes them to ECR and applies Terraform with the new tag. ECS then rolls out with circuit-breaker rollback. It authenticates through GitHub OIDC, with no stored AWS keys.

It runs **only by hand**, only when the repository variable `AWS_DEPLOY_ENABLED` is `true`, and only through the `staging` environment, which must require a reviewer.
