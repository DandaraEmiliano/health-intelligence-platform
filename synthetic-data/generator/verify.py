"""Verify the fixtures are trustworthy as ground truth.

Checks, for every fixture:
  1. The expected report is a valid `document-extraction-result.v1` payload (contracts/events).
  2. pageCount matches the PDF, and every result's excerpt, raw value and name appear on its source page
     (so an extractor reading the PDF text can, in principle, reach the expected answer).
  3. Scanned fixtures have no text layer.
  4. manifest.json hashes match the PDFs.
And for the set: minimum counts and coverage of the hard cases promised in the backlog (E2-T5).

Usage (from synthetic-data/): uv run python -m generator.verify [--dir fixtures/lab-reports]
"""

import argparse
import hashlib
import json
import re
import sys
from collections import Counter
from pathlib import Path

from jsonschema import Draft202012Validator
from pypdf import PdfReader

SCHEMA = Path(__file__).resolve().parents[2] / "contracts/events/document-extraction-result.v1.schema.json"

REQUIRED_TAGS = {
    "multi-page",
    "censored-value",
    "qualitative",
    "multiple-ranges",
    "sex-specific-range",
    "lab-flag",
    "unusual-unit",
    "thousands-separator",
    "prompt-injection",
    "collection-date-missing",
    "scanned-no-text-layer",
}
MIN_FIXTURES, MIN_LAYOUTS = 30, 3


def normalize(text: str) -> str:
    return re.sub(r"\s+", " ", text).strip()


def _wrapper(report: dict) -> dict:
    return {
        "schemaVersion": "1.0",
        "messageId": "00000000-0000-7000-8000-000000000001",
        "jobId": "00000000-0000-7000-8000-000000000002",
        "documentId": "00000000-0000-7000-8000-000000000003",
        "patientId": "00000000-0000-7000-8000-000000000004",
        "extractorVersion": "0.0.0",
        "status": "SUCCEEDED",
        "completedAt": "2026-11-12T00:00:00Z",
        "report": report,
    }


def verify(directory: Path) -> list[str]:
    validator = Draft202012Validator(
        json.loads(SCHEMA.read_text(encoding="utf-8")), format_checker=Draft202012Validator.FORMAT_CHECKER
    )
    errors: list[str] = []
    tags: Counter[str] = Counter()
    layouts: set[str] = set()
    truths = sorted(directory.glob("*.expected.json"))

    for truth_path in truths:
        truth = json.loads(truth_path.read_text(encoding="utf-8"))
        fid = truth["fixtureId"]
        reader = PdfReader(directory / f"{fid}.pdf")
        pages = [normalize(page.extract_text() or "") for page in reader.pages]
        tags.update(truth["tags"])
        layouts.add(truth["layout"])

        if truth["expected"]["status"] == "UNSUPPORTED_SCANNED":
            if any(len(text) > 0 for text in pages):
                errors.append(f"{fid}: scanned fixture has a text layer")
            continue

        report = truth["expected"]["report"]
        errors.extend(f"{fid}: schema: {e.message}" for e in validator.iter_errors(_wrapper(report)))
        if report["pageCount"] != len(pages):
            errors.append(f"{fid}: pageCount {report['pageCount']} != PDF pages {len(pages)}")
        if "SINTÉTICO" not in " ".join(pages):
            errors.append(f"{fid}: synthetic footer missing")
        if report["collectedAtRaw"] and normalize(report["collectedAtRaw"]) not in pages[0]:
            errors.append(f"{fid}: collectedAtRaw not on page 1")

        for result in report["results"]:
            page_text = pages[result["source"]["page"] - 1]
            label = f"{fid} #{result['sequence']} {result['rawName']}"
            if normalize(result["source"]["excerpt"]) not in page_text:
                errors.append(f"{label}: excerpt not found on page {result['source']['page']}")
            if result["measurement"]["rawValue"] not in result["source"]["excerpt"]:
                errors.append(f"{label}: rawValue not inside excerpt")
            if normalize(result["rawName"]) not in page_text:
                errors.append(f"{label}: rawName not on page")
            if result["referenceRange"]["rawText"] and normalize(result["referenceRange"]["rawText"]) not in page_text:
                errors.append(f"{label}: reference range text not on page")

    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    for entry in manifest["fixtures"]:
        digest = hashlib.sha256((directory / f"{entry['fixtureId']}.pdf").read_bytes()).hexdigest()
        if digest != entry["pdfSha256"]:
            errors.append(f"{entry['fixtureId']}: manifest hash mismatch")

    if len(truths) < MIN_FIXTURES:
        errors.append(f"only {len(truths)} fixtures (minimum {MIN_FIXTURES})")
    text_layouts = layouts - {"scanned"}
    if len(text_layouts) < MIN_LAYOUTS:
        errors.append(f"only {len(text_layouts)} layouts (minimum {MIN_LAYOUTS})")
    missing = REQUIRED_TAGS - set(tags)
    if missing:
        errors.append(f"hard cases not covered: {sorted(missing)}")

    print(f"fixtures: {len(truths)}  layouts: {sorted(text_layouts)}")
    print(
        "coverage: "
        + ", ".join(f"{tag}={count}" for tag, count in sorted(tags.items()) if not tag.startswith("layout"))
    )
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dir", type=Path, default=Path("fixtures/lab-reports"))
    errors = verify(parser.parse_args().dir)
    for error in errors[:50]:
        print("FAIL", error)
    if errors:
        print(f"{len(errors)} problem(s)")
        return 1
    print("ALL FIXTURE CHECKS PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
