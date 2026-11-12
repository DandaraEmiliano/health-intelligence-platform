"""Compare two fixture directories by content.

PDF bytes are not stable across environments (Flate compression output depends on the zlib build),
so determinism is checked where it matters for evaluation:
  * ground-truth JSON files must be byte-identical;
  * every PDF must have the same page count and the same extracted text on every page.

Usage: uv run python -m generator.compare <committed-dir> <regenerated-dir>
"""

import sys
from pathlib import Path

from pypdf import PdfReader


def compare(committed: Path, regenerated: Path) -> list[str]:
    errors: list[str] = []
    committed_names = {p.name for p in committed.iterdir() if p.name != "manifest.json"}
    regenerated_names = {p.name for p in regenerated.iterdir() if p.name != "manifest.json"}
    errors.extend(f"only in committed: {name}" for name in sorted(committed_names - regenerated_names))
    errors.extend(f"only in regenerated: {name}" for name in sorted(regenerated_names - committed_names))

    for name in sorted(committed_names & regenerated_names):
        a, b = committed / name, regenerated / name
        if name.endswith(".json"):
            if a.read_bytes() != b.read_bytes():
                errors.append(f"{name}: ground truth differs")
            continue
        pages_a = [page.extract_text() for page in PdfReader(a).pages]
        pages_b = [page.extract_text() for page in PdfReader(b).pages]
        if pages_a != pages_b:
            errors.append(f"{name}: PDF text differs ({len(pages_a)} vs {len(pages_b)} pages)")
    return errors


def main() -> int:
    committed, regenerated = (Path(arg) for arg in sys.argv[1:3])
    errors = compare(committed, regenerated)
    for error in errors:
        print("FAIL", error)
    if errors:
        return 1
    print("regenerated fixtures match the committed ones (ground truth and PDF text)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
