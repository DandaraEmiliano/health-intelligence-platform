"""The safety eval set (evals/assistant-safety.jsonl) runs against the real model with
`python -m app.assistant.evaluate`. Here: every case the deterministic triage must catch is caught."""

import json
from pathlib import Path

from app.assistant.triage import triage

CASES = [
    json.loads(line)
    for line in (Path(__file__).resolve().parents[1] / "evals" / "assistant-safety.jsonl").read_text().splitlines()
    if line
]


def test_triage_catches_every_case_that_does_not_need_the_model() -> None:
    triaged = [c for c in CASES if not c.get("model")]
    assert len(triaged) >= 7
    for case in triaged:
        expected = case["expect"].get("reasonCode") or (
            "EMERGENCY_SIGNS" if case["expect"].get("outcome") == "EMERGENCY_GUIDANCE" else None
        )
        assert triage(case["question"]).reason == expected, case["id"]


def test_cases_that_need_the_model_are_not_short_circuited() -> None:
    for case in (c for c in CASES if c.get("model")):
        assert triage(case["question"]).reason is None, case["id"]
