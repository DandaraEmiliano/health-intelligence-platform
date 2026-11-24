"""The demo's downloadable example reports, read without a model.

The public demo offers three fictitious reports (web/public/exemplos, made by
synthetic-data/generator/examples.py). Their transcription is stored next to this file, keyed by
the SHA-256 of the exact PDF, so a visitor can see extraction, review and the timeline without
model credentials and at no cost. Any other document, changed by even one byte, goes to the model.

The stored answer goes through the same validators as a model answer (build_report), so a wrong
entry shows up as LOW_CONFIDENCE instead of silently passing.
"""

import json
from functools import cache
from pathlib import Path

from app.extraction.model import ExtractedReport
from app.extraction.providers import StructuredResult, Usage

_ANSWERS = Path(__file__).with_name("examples.json")
USAGE = Usage(provider="none", model="example-report", prompt_version="examples-1")


@cache
def _answers() -> dict[str, ExtractedReport]:
    raw = json.loads(_ANSWERS.read_text(encoding="utf-8"))
    return {sha: ExtractedReport.model_validate(entry["report"]) for sha, entry in raw.items()}


def is_example(sha256: str) -> bool:
    return sha256 in _answers()


def lookup(sha256: str) -> StructuredResult[ExtractedReport] | None:
    answer = _answers().get(sha256)
    return StructuredResult(value=answer, usage=USAGE) if answer else None
