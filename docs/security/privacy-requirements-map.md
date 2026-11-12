# Privacy requirements map (LGPD), v1

- **Status:** Draft for review (Phase 2)

> **This is not a compliance assessment.** It maps LGPD (Lei 13.709/2018) topics to engineering controls so that legal and privacy specialists can review them. Items marked **Legal validation required** cannot be settled by engineering. Implementing these controls does not make the platform compliant.

Health data is **sensitive personal data** (art. 5, II), so the stricter rules of art. 11 apply.

| Topic | LGPD reference | Engineering control in this design | Status | Legal validation required |
| --- | --- | --- | --- | --- |
| Legal basis for processing health data | Art. 11 | Specific, highlighted consent for AI processing (`AI_PROCESSING`); versioned acceptance of terms and privacy policy | Designed | **Yes**: which basis applies to each processing purpose (storage, extraction, assistant), and the consent wording |
| Purpose limitation and minimization | Art. 6, I and III | No national ID (CPF), address or phone in the MVP; the fact pack sends only the data relevant to the question; no data used for model training | Designed | Review of collected fields and purposes |
| Transparency | Art. 6, VI; art. 9 | Consent screens explain the external AI processing; privacy policy | Wording pending | **Yes**: privacy notice text |
| International transfer to the LLM provider | Art. 33 | Provider selection criteria (D3): region, zero retention, DPA; evaluate a Brazil-region option | Open | **Yes**: transfer mechanism and provider contract |
| Data subject rights: access and portability | Art. 18, II and V | Data export (JSON) planned for Phase 7 | Planned | Format and deadlines |
| Data subject rights: deletion | Art. 18, VI | Account deletion: immediate block plus a scheduled, audited purge of DB, S3, embeddings and the IdP user | Designed (MVP) | Retention exceptions (for example, audit logs) |
| Consent revocation | Art. 8, §5; art. 18, IX | Revoking AI consent disables AI features and purges embeddings | Designed (MVP) | Effects on data already processed |
| Security | Art. 46 | See [threat model](threat-model.md): encryption in transit and at rest, access control, isolation, audit | Designed | Adequacy review |
| Incident communication | Art. 48 | Incident response runbook (Phase 7) | Planned | **Yes**: ANPD and data subject notification process |
| Records of processing | Art. 37 | Audit trail of sensitive operations; this document as a starting inventory | Partial | Formal record of processing activities |
| Data protection impact report (RIPD) | Art. 38 | Inputs: this map, the threat model and the architecture docs | Not started | **Yes**: the RIPD itself |
| Data processors (operators) | Art. 39 | Inventory: cloud provider (AWS), LLM provider, email provider (future) | Partial | **Yes**: contracts |
| Retention periods | Art. 15, 16 | Configurable purge job; audit retention configurable separately | Designed | **Yes**: the periods themselves |
| Children and adolescents | Art. 14 | MVP limited to adult account holders; dependents out of scope (D7) | Decided | **Yes**: before dependents are supported |

## Related regulatory topic (not LGPD)

- **ANVISA, software as a medical device (RDC 657/2022):** product rules forbid diagnosis, prescription and dosage advice, and guardrails enforce them. Whether the product falls under SaMD regulation **requires regulatory validation**.
