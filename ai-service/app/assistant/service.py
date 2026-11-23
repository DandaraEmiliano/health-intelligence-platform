"""One assistant answer: triage, prompt, model, checks, at most one regeneration, safe fallback."""

import json
import logging
from html import escape

from app.assistant.checks import violations
from app.assistant.contract import (
    AnswerRequest,
    AnswerResponse,
    Care,
    Draft,
    Explanation,
    Safety,
    Source,
    Usage,
)
from app.assistant.glossary import entries_for
from app.assistant.triage import (
    DOSAGE_ANSWER,
    EMERGENCY_ANSWER,
    FAILED_ANSWER,
    PRESCRIPTION_ANSWER,
    triage,
)
from app.extraction.prompts import RenderedPrompt, _load
from app.extraction.providers import LlmProvider, ProviderError

log = logging.getLogger(__name__)

PROMPT_VERSION = "assistant/v1"


class AssistantUnavailable(Exception):
    """The provider failed in a way the patient should retry later (503 AI_UNAVAILABLE)."""


def render(
    request: AnswerRequest, glossary: dict[str, dict[str, str]], feedback: list[str] | None = None
) -> RenderedPrompt:
    facts = [
        {
            "fact_id": f.factId,
            "label": f.label,
            "collected_at": f.collectedAt,
            "value": f.displayValue,
            "unit": f.unit,
            "reference_range": f.referenceRange,
            "flag": f.flag,
            "flag_source": f.flagSource,
            "laboratory": f.laboratoryName,
        }
        for f in request.factPack.results
    ]
    records = {
        "patient": request.factPack.patient.model_dump() if request.factPack.patient else None,
        "allergies": [a.model_dump() for a in request.factPack.allergies],
        "medications": [m.model_dump() for m in request.factPack.medications],
        "conditions": [c.model_dump() for c in request.factPack.conditions],
    }
    history = "\n".join(f"{t.role}: {escape(t.content, quote=False)}" for t in request.history)
    parts = [
        "<facts>",
        escape(json.dumps({"results": facts, **records}, ensure_ascii=False), quote=False),
        "</facts>",
        "<glossary>",
        escape(json.dumps(glossary, ensure_ascii=False), quote=False),
        "</glossary>",
        "<history>",
        history,
        "</history>",
        "<question>",
        escape(request.question, quote=False),
        "</question>",
    ]
    if feedback:
        parts.append(
            "Your previous answer could not be shown because: "
            + "; ".join(feedback[:10])
            + ". Write it again following the rules."
        )
    return RenderedPrompt(version=PROMPT_VERSION, system=_load(PROMPT_VERSION), user="\n".join(parts))


def _fixed(request: AnswerRequest, reason: str) -> AnswerResponse:
    emergency = reason == "EMERGENCY_SIGNS"
    return AnswerResponse(
        requestId=request.requestId,
        outcome="EMERGENCY_GUIDANCE" if emergency else "REFUSED",
        answer={
            "EMERGENCY_SIGNS": EMERGENCY_ANSWER,
            "DOSAGE_CHANGE_REQUEST": DOSAGE_ANSWER,
            "PRESCRIPTION_REQUEST": PRESCRIPTION_ANSWER,
        }[reason],
        citedFactIds=[],
        explanations=[],
        uncertainty="LOW",
        insufficientData=False,
        seekProfessionalCare=Care(recommended=True, urgency="EMERGENCY" if emergency else "ROUTINE"),
        suggestedQuestionsForDoctor=[],
        safety=Safety(refused=True, reasonCode=reason),
        promptVersion=PROMPT_VERSION,
    )


def answer(request: AnswerRequest, provider: LlmProvider | None) -> AnswerResponse:
    decided = triage(request.question)
    if decided.reason:
        log.info("assistant request=%s outcome=fixed reason=%s", request.requestId, decided.reason)
        return _fixed(request, decided.reason)
    if provider is None:
        raise AssistantUnavailable("no provider configured")

    glossary = entries_for({f.analyteCode for f in request.factPack.results if f.analyteCode})
    feedback: list[str] | None = None
    usage: Usage | None = None
    for attempt in range(2):
        try:
            result = provider.generate_structured(render(request, glossary, feedback), Draft)
        except ProviderError as e:
            log.warning("assistant request=%s provider error=%s", request.requestId, e.code)
            raise AssistantUnavailable(e.code) from e
        usage = Usage(
            provider=result.usage.provider,
            model=result.usage.model,
            inputTokens=(usage.inputTokens if usage else 0) + result.usage.input_tokens,
            outputTokens=(usage.outputTokens if usage else 0) + result.usage.output_tokens,
            latencyMs=(usage.latencyMs if usage else 0) + result.usage.latency_ms,
        )
        draft = result.value
        feedback = violations(draft, request.factPack, request.question, set(glossary))
        if not feedback:
            outcome = "REFUSED" if draft.refused else ("PASSED" if attempt == 0 else "REGENERATED")
            log.info("assistant request=%s outcome=%s attempts=%s", request.requestId, outcome, attempt + 1)
            return _response(request, draft, outcome, usage)
        log.info("assistant request=%s rejected draft (%s problems)", request.requestId, len(feedback))

    log.warning("assistant request=%s outcome=FAILED", request.requestId)
    return AnswerResponse(
        requestId=request.requestId,
        outcome="FAILED",
        answer=FAILED_ANSWER,
        citedFactIds=[],
        explanations=[],
        uncertainty="HIGH",
        insufficientData=True,
        seekProfessionalCare=Care(recommended=True, urgency="ROUTINE"),
        suggestedQuestionsForDoctor=[],
        safety=Safety(refused=False, reasonCode=None),
        promptVersion=PROMPT_VERSION,
        usage=usage,
    )


def _response(request: AnswerRequest, draft: Draft, outcome: str, usage: Usage | None) -> AnswerResponse:
    cited = list(dict.fromkeys(draft.cited_fact_ids))
    return AnswerResponse(
        requestId=request.requestId,
        outcome=outcome,
        answer=draft.answer,
        citedFactIds=cited,
        explanations=[
            Explanation(text=e.text, sources=[Source(id=g) for g in e.glossary_ids]) for e in draft.explanations
        ],
        uncertainty=draft.uncertainty,
        insufficientData=draft.insufficient_data,
        seekProfessionalCare=Care(
            recommended=draft.seek_professional_care.recommended, urgency=draft.seek_professional_care.urgency
        ),
        suggestedQuestionsForDoctor=draft.suggested_questions_for_doctor[:5],
        safety=Safety(refused=draft.refused, reasonCode=draft.refusal_reason if draft.refused else None),
        promptVersion=PROMPT_VERSION,
        usage=usage,
    )
