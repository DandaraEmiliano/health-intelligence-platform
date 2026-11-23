"""Fact packs from synthetic fixtures, service tokens signed by a local key, and an app wired for tests."""

import json
import time
import uuid

import jwt
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi.testclient import TestClient

from app.assistant.contract import AnswerRequest, Draft, DraftCare, DraftExplanation
from app.auth import get_key_resolver
from app.main import create_app
from tests.extraction_fixtures import expected

KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)
ISSUER = "http://localhost:8180/realms/hip"


def token(audience: str = "ai-service", client: str = "core-api-service", expires_in: int = 300, key=KEY) -> str:
    now = int(time.time())
    claims = {
        "iss": ISSUER,
        "aud": audience,
        "azp": client,
        "sub": "service-account",
        "iat": now,
        "exp": now + expires_in,
    }
    return jwt.encode(claims, key, algorithm="RS256")


def client_with(provider) -> TestClient:
    app = create_app()
    app.dependency_overrides[get_key_resolver] = lambda: lambda _token: KEY.public_key()
    test_client = TestClient(app)
    test_client.__enter__()
    app.state.assistant_provider = provider
    return test_client


FIXTURE = "SP-001-2022-03-16-beta"


def fact_pack(fixture_id: str = FIXTURE) -> dict:
    """The fixture's results as the core would send them after a finished review."""
    truth = expected(fixture_id)
    report = truth["expected"]["report"]
    results = []
    for r in report["results"]:
        m = r["measurement"]
        rng = r["referenceRange"]
        results.append(
            {
                "factId": str(uuid.uuid5(uuid.NAMESPACE_URL, f"{fixture_id}/{r['sequence']}")),
                "analyteCode": truth["analyteCodes"][str(r["sequence"])],
                "label": r["rawName"],
                "collectedAt": report["collectedAt"],
                "kind": m["kind"],
                "displayValue": m["rawValue"],
                "valueNumeric": m.get("valueNumeric"),
                "comparator": m.get("comparator"),
                "unit": r["unit"]["raw"],
                "referenceRange": rng["rawText"],
                "low": rng["low"],
                "high": rng["high"],
                "flag": r.get("labFlag"),
                "flagSource": "LABORATORY" if r.get("labFlag") else None,
                "laboratoryName": report["laboratoryName"],
            }
        )
    return {"patient": {"ageYears": 34, "biologicalSex": "FEMALE"}, "results": results,
            "allergies": [{"substance": "Penicilina", "type": "MEDICATION", "severity": "SEVERE"}],
            "medications": [
                {"name": "Levotiroxina", "dosageText": "50 mcg", "frequencyText": "1x ao dia", "active": True}
            ],
            "conditions": []}  # fmt: skip


def request(question: str, pack: dict | None = None) -> dict:
    return {"schemaVersion": "1.0", "requestId": str(uuid.uuid4()), "patientId": str(uuid.uuid4()),
            "question": question, "history": [], "factPack": pack or fact_pack()}  # fmt: skip


def fact(pack: dict, code: str) -> dict:
    return next(f for f in pack["results"] if f["analyteCode"] == code)


def draft(answer: str, cited: list[str], explanations: list[tuple[str, list[str]]] | None = None, **extra) -> Draft:
    return Draft(
        answer=answer,
        cited_fact_ids=cited,
        explanations=[DraftExplanation(text=t, glossary_ids=g) for t, g in (explanations or [])],
        uncertainty=extra.get("uncertainty", "LOW"),
        insufficient_data=extra.get("insufficient_data", False),
        seek_professional_care=DraftCare(
            recommended=extra.get("recommended", True), urgency=extra.get("urgency", "ROUTINE")
        ),
        suggested_questions_for_doctor=extra.get("questions", ["Esse valor precisa de acompanhamento?"]),
        refused=extra.get("refused", False),
        refusal_reason=extra.get("refusal_reason"),
    )


def parse(body: dict) -> AnswerRequest:
    return AnswerRequest.model_validate(body)


def response_schema() -> dict:
    from tests.extraction_fixtures import REPO

    return json.loads((REPO / "contracts" / "internal" / "assistant-answer-response.v1.schema.json").read_text())
