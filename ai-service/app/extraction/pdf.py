"""Text layer of a PDF. Parsing untrusted PDFs happens only here, in ai-service (threat model T06)."""

import io
import re
from dataclasses import dataclass

from pypdf import PdfReader
from pypdf.errors import FileNotDecryptedError, PdfReadError

MAX_PAGES = 200
# Fewer meaningful characters than this per page means there is no usable text layer.
MIN_CHARS_PER_PAGE = 40


class PdfProblem(Exception):
    def __init__(self, code: str, detail: str) -> None:
        super().__init__(detail)
        self.code = code
        self.detail = detail


@dataclass(frozen=True)
class PdfText:
    pages: list[str]

    @property
    def page_count(self) -> int:
        return len(self.pages)

    @property
    def is_scanned(self) -> bool:
        meaningful = sum(len(re.sub(r"\s", "", page)) for page in self.pages)
        return meaningful < MIN_CHARS_PER_PAGE * max(1, len(self.pages))


def read_pdf(content: bytes) -> PdfText:
    try:
        reader = PdfReader(io.BytesIO(content))
        if reader.is_encrypted:
            raise PdfProblem("PDF_ENCRYPTED", "PDF is encrypted")
        if len(reader.pages) > MAX_PAGES:
            raise PdfProblem("PDF_CORRUPTED", f"more than {MAX_PAGES} pages")
        return PdfText(pages=[page.extract_text() or "" for page in reader.pages])
    except PdfProblem:
        raise
    except FileNotDecryptedError as e:
        raise PdfProblem("PDF_ENCRYPTED", "PDF is encrypted") from e
    except (PdfReadError, ValueError, KeyError, TypeError) as e:
        raise PdfProblem("PDF_CORRUPTED", type(e).__name__) from e


def normalize_whitespace(text: str) -> str:
    return re.sub(r"\s+", " ", text).strip()
