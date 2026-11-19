"""Evaluates the extractor against the synthetic ground truth (synthetic-data/fixtures).

    uv run python -m app.extraction.evaluate [--limit N] [--only FIXTURE_ID ...]

Calls the configured LLM provider for every text fixture (this costs money with a real
provider) and reports, per field, layout and hard-case tag, how often the output matches the
ground truth exactly. Results are matched by test name. Writes eval-report.json; no document
text or prompt is written, only fixture ids, field names and outcomes.
"""

import argparse
import hashlib
import json
import sys
from collections import Counter, defaultdict
from datetime import date
from pathlib import Path

from app.config import get_settings
from app.extraction.pipeline import extract

REPO = Path(__file__).resolve().parents[3]
FIXTURES = REPO / "synthetic-data" / "fixtures" / "lab-reports"

FIELDS = {
    "rawValue": lambda r: r["measurement"]["rawValue"],
    "valueNumeric": lambda r: r["measurement"].get("valueNumeric"),
    "comparator": lambda r: r["measurement"].get("comparator"),
    "unit": lambda r: r["unit"]["raw"],
    "rangeRawText": lambda r: r["referenceRange"]["rawText"],
    "rangeLow": lambda r: r["referenceRange"]["low"],
    "rangeHigh": lambda r: r["referenceRange"]["high"],
    "appliesTo": lambda r: r["referenceRange"]["appliesTo"],
    "labFlag": lambda r: r.get("labFlag"),
    "page": lambda r: r["source"]["page"],
    "excerpt": lambda r: " ".join(r["source"]["excerpt"].split()),
    "warnings": lambda r: sorted(w["code"] for w in r.get("warnings", [])),
}
REPORT_FIELDS = ["laboratoryName", "collectedAt", "reportedAt", "pageCount"]


def _request(fixture_id: str, content: bytes) -> dict:
    return {
        "schemaVersion": "1.0", "messageId": "eval", "jobId": "eval", "documentId": fixture_id,
        "patientId": "eval", "extractorVersion": "1.0.0", "requestedAt": "2026-01-01T00:00:00Z",
        "document": {"bucket": "eval", "objectKey": "eval", "sha256": hashlib.sha256(content).hexdigest(),
                     "contentType": "application/pdf", "sizeBytes": len(content)},
    }  # fmt: skip


def evaluate(provider, fixture_ids: list[str]) -> dict:
    field_hits: dict[str, Counter] = defaultdict(Counter)
    by_layout: dict[str, Counter] = defaultdict(Counter)
    by_tag: dict[str, Counter] = defaultdict(Counter)
    statuses = Counter()
    mismatches = []
    tokens = Counter()
    for fixture_id in fixture_ids:
        truth = json.loads((FIXTURES / f"{fixture_id}.expected.json").read_text(encoding="utf-8"))
        content = (FIXTURES / f"{fixture_id}.pdf").read_bytes()
        result = extract(_request(fixture_id, content), lambda b, k, c=content: c, provider,
                         today=lambda: date(2026, 11, 19), last_attempt=True)  # fmt: skip
        usage = result.get("usage", {})
        tokens["input"] += usage.get("inputTokens", 0)
        tokens["output"] += usage.get("outputTokens", 0)
        want = truth["expected"]
        statuses["status_ok" if result["status"] == want["status"] else "status_wrong"] += 1
        if want["status"] != "SUCCEEDED" or result["status"] != "SUCCEEDED":
            if result["status"] != want["status"]:
                mismatches.append({"fixture": fixture_id, "field": "status", "got": result["status"],
                                   "want": want["status"], "failure": result.get("failure")})  # fmt: skip
            continue
        got_report, want_report = result["report"], want["report"]
        for field in REPORT_FIELDS:
            field_hits[f"report.{field}"][got_report.get(field) == want_report.get(field)] += 1
        got_by_name = {" ".join(r["rawName"].split()).casefold(): r for r in got_report["results"]}
        statuses["results_expected"] += len(want_report["results"])
        statuses["results_returned"] += len(got_report["results"])
        for wanted in want_report["results"]:
            got = got_by_name.get(" ".join(wanted["rawName"].split()).casefold())
            all_ok = got is not None
            for field, get in FIELDS.items():
                ok = got is not None and get(got) == get(wanted)
                field_hits[field][ok] += 1
                all_ok &= ok
                if got is not None and not ok:
                    mismatches.append({"fixture": fixture_id, "test": wanted["rawName"], "field": field,
                                       "got": get(got), "want": get(wanted)})  # fmt: skip
            if got is None:
                mismatches.append({"fixture": fixture_id, "test": wanted["rawName"], "field": "missing"})
            statuses["results_found"] += got is not None
            by_layout[truth["layout"]][all_ok] += 1
            for tag in truth["tags"]:
                by_tag[tag][all_ok] += 1

    def rate(counter: Counter) -> float:
        total = counter[True] + counter[False]
        return round(counter[True] / total, 4) if total else 1.0

    return {
        "fixtures": len(fixture_ids),
        "statuses": dict(statuses),
        "fieldAccuracy": {k: rate(v) for k, v in sorted(field_hits.items())},
        "resultExactByLayout": {k: rate(v) for k, v in sorted(by_layout.items())},
        "resultExactByTag": {k: rate(v) for k, v in sorted(by_tag.items())},
        "tokens": dict(tokens),
        "mismatches": mismatches,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--limit", type=int)
    parser.add_argument("--only", nargs="*")
    parser.add_argument("--out", default="eval-report.json")
    args = parser.parse_args()

    from app.main import _provider

    ids = sorted(p.name.removesuffix(".expected.json") for p in FIXTURES.glob("*.expected.json"))
    if args.only:
        ids = [i for i in ids if i in args.only]
    ids = ids[: args.limit] if args.limit else ids
    settings = get_settings()
    target = f"{settings.llm_provider}:{settings.llm_model} effort={settings.llm_effort}"
    print(f"Evaluating {len(ids)} fixtures with {target}", file=sys.stderr)
    report = evaluate(_provider(settings), ids)
    report["model"] = settings.llm_model
    report["effort"] = settings.llm_effort
    Path(args.out).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    summary = {k: report[k] for k in ("statuses", "fieldAccuracy", "resultExactByLayout", "tokens")}
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
