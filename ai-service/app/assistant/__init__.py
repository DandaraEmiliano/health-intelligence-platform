"""The assistant (ADR 0004): answers from a closed fact pack of the patient's verified data.

Layers, in order: deterministic triage (emergencies, prescription and dosage requests never reach
the model), the LLM with structured output, then checks on what it wrote (every number from the
facts, only known fact and glossary ids, no prescribing or diagnosing language). A failed check is
regenerated once; a second failure returns a safe fallback. No question or answer text is logged.
"""
