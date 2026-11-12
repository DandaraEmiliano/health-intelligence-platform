"""Validate contract examples against their JSON Schemas.

Every file in examples/valid must pass; every file in examples/invalid must fail.
The schema is chosen by file name prefix: "extraction-request"/"request-" -> request schema,
everything else -> result schema.

Usage: python contracts/scripts/validate_examples.py
Requires: jsonschema[format-nongpl] >= 4.18
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

from jsonschema import Draft202012Validator

EVENTS_DIR = Path(__file__).resolve().parent.parent / "events"
EXAMPLES_DIR = EVENTS_DIR / "examples"

SCHEMAS = {
    "request": EVENTS_DIR / "document-extraction-request.v1.schema.json",
    "result": EVENTS_DIR / "document-extraction-result.v1.schema.json",
}


def load_validator(path: Path) -> Draft202012Validator:
    schema = json.loads(path.read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema, format_checker=Draft202012Validator.FORMAT_CHECKER)


def schema_for(example: Path) -> str:
    name = example.name
    return "request" if name.startswith(("extraction-request", "request-")) else "result"


def main() -> int:
    validators = {key: load_validator(path) for key, path in SCHEMAS.items()}
    failures: list[str] = []

    for example in sorted((EXAMPLES_DIR / "valid").glob("*.json")):
        errors = list(validators[schema_for(example)].iter_errors(json.loads(example.read_text(encoding="utf-8"))))
        if errors:
            failures.append(f"valid/{example.name} should pass but failed: {errors[0].message}")
        else:
            print(f"PASS  valid/{example.name}")

    for example in sorted((EXAMPLES_DIR / "invalid").glob("*.json")):
        errors = list(validators[schema_for(example)].iter_errors(json.loads(example.read_text(encoding="utf-8"))))
        if not errors:
            failures.append(f"invalid/{example.name} should fail but passed")
        else:
            print(f"PASS  invalid/{example.name} rejected: {errors[0].message[:90]}")

    for failure in failures:
        print(f"FAIL  {failure}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
