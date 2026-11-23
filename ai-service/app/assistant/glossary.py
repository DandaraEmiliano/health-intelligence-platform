"""Curated glossary (ai-service/glossary/pt-BR.json): the only general knowledge the answer cites."""

import json
from functools import lru_cache
from pathlib import Path

GLOSSARY_FILE = Path(__file__).resolve().parents[2] / "glossary" / "pt-BR.json"


@lru_cache
def glossary() -> dict[str, dict[str, str]]:
    data = json.loads(GLOSSARY_FILE.read_text(encoding="utf-8"))
    return {key: value for key, value in data.items() if not key.startswith("_")}


def entries_for(analyte_codes: set[str]) -> dict[str, dict[str, str]]:
    """Entries for the analytes in the fact pack, plus the general one about reference ranges."""
    all_entries = glossary()
    wanted = {code for code in analyte_codes if code in all_entries} | {"REFERENCE_RANGE"}
    return {code: all_entries[code] for code in sorted(wanted)}
