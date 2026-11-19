"""Versioned prompts (ADR 0008). The version travels in every result as usage.promptVersion."""

from dataclasses import dataclass
from functools import lru_cache
from html import escape
from pathlib import Path

PROMPTS_DIR = Path(__file__).resolve().parents[2] / "prompts"
EXTRACTION_PROMPT_VERSION = "extraction/v1"


@dataclass(frozen=True)
class RenderedPrompt:
    version: str
    system: str
    user: str


@lru_cache
def _load(version: str) -> str:
    return (PROMPTS_DIR / f"{version}.md").read_text(encoding="utf-8")


def render_extraction(pages: list[str]) -> RenderedPrompt:
    """Document text is untrusted: it is escaped and delimited so it cannot close the tags."""
    body = "\n".join(
        f'<page number="{i}">\n{escape(text, quote=False)}\n</page>' for i, text in enumerate(pages, start=1)
    )
    return RenderedPrompt(
        version=EXTRACTION_PROMPT_VERSION,
        system=_load(EXTRACTION_PROMPT_VERSION),
        user=f"<document>\n{body}\n</document>\n\nTranscribe this report.",
    )
