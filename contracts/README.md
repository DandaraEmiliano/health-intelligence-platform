# Contracts

Versioned contracts between `core-api` and `ai-service`. A contract change is made in the same pull request as the code on both sides.

| Contract | Direction | Queue / endpoint |
| --- | --- | --- |
| [`core-api.openapi.yaml`](core-api.openapi.yaml) | clients → core-api | Public REST API `/api/v1` (OpenAPI 3.1, design draft) |
| [`document-extraction-request.v1`](events/document-extraction-request.v1.schema.json) | core-api → ai-service | `document-extraction-requests` |
| [`document-extraction-result.v1`](events/document-extraction-result.v1.schema.json) | ai-service → core-api | `document-extraction-results` |

Rules:

- Every message carries `schemaVersion`. Additive, optional fields are a minor version; anything else is a new major version (`.v2`), published in parallel until consumers migrate.
- Decimals travel as strings with a dot separator, never as JSON numbers.
- Unknown fields are rejected (`additionalProperties: false`), so drift is caught in tests instead of being silently ignored.
- `examples/valid` must pass and `examples/invalid` must fail. Run `python contracts/scripts/validate_examples.py` (requires `jsonschema[format-nongpl]>=4.18`). CI will run it from Phase 3.
