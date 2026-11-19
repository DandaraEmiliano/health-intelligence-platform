"""Synthetic fixtures (synthetic-data/) and a fake LLM that answers with their ground truth.

The fake turns the expected `report` back into what the LLM is asked to return (raw strings
only), so the tests prove that everything derived in code reproduces the ground truth.
"""

import hashlib
import json
from pathlib import Path

from app.extraction.model import ExtractedReport, ExtractedResult

REPO = Path(__file__).resolve().parents[2]
FIXTURES = REPO / "synthetic-data" / "fixtures" / "lab-reports"
CONTRACTS = REPO / "contracts" / "events"


def fixture_ids() -> list[str]:
    return sorted(p.name.removesuffix(".expected.json") for p in FIXTURES.glob("*.expected.json"))


def expected(fixture_id: str) -> dict:
    return json.loads((FIXTURES / f"{fixture_id}.expected.json").read_text(encoding="utf-8"))


def pdf_bytes(fixture_id: str) -> bytes:
    return (FIXTURES / f"{fixture_id}.pdf").read_bytes()


def llm_answer_from_ground_truth(report: dict) -> ExtractedReport:
    return ExtractedReport(
        is_lab_report=True,
        laboratory_name=report.get("laboratoryName"),
        collected_at_raw=report.get("collectedAtRaw"),
        reported_at_raw=report.get("reportedAt"),
        results=[
            ExtractedResult(
                raw_name=r["rawName"],
                raw_value=r["measurement"]["rawValue"],
                unit_raw=r["unit"]["raw"],
                reference_range_raw=r["referenceRange"]["rawText"],
                lab_flag=r.get("labFlag"),
                method=r.get("method"),
                page=r["source"]["page"],
                excerpt=r["source"]["excerpt"],
            )
            for r in report["results"]
        ],
    )


def request_for(fixture_id: str) -> dict:
    content = pdf_bytes(fixture_id)
    return {
        "schemaVersion": "1.0",
        "messageId": "0199a5a0-0000-7000-8000-000000000001",
        "jobId": "0199a5a0-0000-7000-8000-000000000002",
        "documentId": "0199a5a0-0000-7000-8000-000000000003",
        "patientId": "0199a5a0-0000-7000-8000-000000000004",
        "extractorVersion": "1.0.0",
        "document": {
            "bucket": "hip-documents-local",
            "objectKey": "documents/0199a5a0-0000-7000-8000-000000000003",
            "sha256": hashlib.sha256(content).hexdigest(),
            "contentType": "application/pdf",
            "sizeBytes": len(content),
        },
        "requestedAt": "2026-11-19T12:00:00Z",
    }
