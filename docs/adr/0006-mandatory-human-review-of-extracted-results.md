# ADR 0006: Mandatory human review of extracted results

- **Status:** Proposed
- **Date:** 2026-11-12
- **Deciders:** Dandara Emiliano (owner), Claude (architect)

## Context

Laboratory PDFs vary in layout, units, decimal separators and how reference ranges are printed. Any extractor, rule-based or LLM-based, will sometimes swap a value and its range, misread a unit, merge two lines or attach a result to the wrong test. A wrong value presented as fact can mislead a patient about their health, and the AI assistant would then explain wrong data with confidence.

Confidence scores that LLMs report about their own output are not calibrated probabilities.

## Decision

**No extracted result is treated as true until the patient reviews it.**

- Extracted results are stored as `PENDING_REVIEW`, together with an immutable snapshot of what the extractor produced, the source page and the source excerpt.
- Deterministic validators run before review and attach warnings (non-numeric value, unknown unit, low above high, future collection date, duplicates).
- The patient confirms, corrects or rejects each result, or confirms all of them at once. A correction keeps the original snapshot (`CORRECTED` status) and is audited.
- A report becomes `VERIFIED` only when no result is pending. Only `VERIFIED` and `CORRECTED` results feed timeline values, analytics and the AI assistant, and only then are they indexed for retrieval.
- A verified result is immutable. A later correction is a new, audited revision.

## Alternatives considered

| Option | Pros | Cons |
| --- | --- | --- |
| Auto-accept above a confidence threshold | Less friction | The threshold rests on uncalibrated scores; silent errors reach analytics and the AI |
| No review, show a disclaimer | Least friction | The disclaimer does not prevent harm and contradicts the product principle |
| Review by staff (human-in-the-loop operators) | Patient does less work | Staff would see sensitive data (more access, more risk), plus cost and latency; not viable for the B2C MVP |

## Consequences

- Review is a core user journey and needs a usable interface (decision D1: a minimal frontend after Phase 4).
- Extraction quality directly drives review effort, so the evaluation harness measures per-field precision and recall on the synthetic golden set.
- This decision can be revisited once real usage data exists (for example, pre-confirming fields that are never corrected), through a new ADR.
