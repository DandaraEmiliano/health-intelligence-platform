"""One extraction job: download, verify, read, transcribe, validate. Returns the result message."""

import hashlib
import logging
from collections.abc import Callable
from datetime import UTC, date, datetime
from uuid import uuid4

from app.extraction.model import ExtractedReport
from app.extraction.pdf import PdfProblem, read_pdf
from app.extraction.prompts import render_extraction
from app.extraction.providers import LlmProvider, ProviderError
from app.extraction.report import build_report

log = logging.getLogger(__name__)

SCHEMA_VERSION = "1.0"


class DocumentMissing(Exception):
    pass


class Retry(Exception):
    """A transient failure: leave the message on the queue so it is delivered again."""


def extract(
    request: dict,
    fetch: Callable[[str, str], bytes],
    provider: LlmProvider,
    today: Callable[[], date] = lambda: datetime.now(UTC).date(),
    last_attempt: bool = False,
) -> dict:
    """Never logs document content or results (threat model T10): only ids and codes."""
    job = request["jobId"]
    document = request["document"]

    def finish(status: str, **extra: object) -> dict:
        message = {
            "schemaVersion": SCHEMA_VERSION,
            "messageId": str(uuid4()),
            "jobId": job,
            "documentId": request["documentId"],
            "patientId": request["patientId"],
            "extractorVersion": request["extractorVersion"],
            "status": status,
            "completedAt": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
        }
        message.update({k: v for k, v in extra.items() if v is not None})
        log.info(
            "extraction job=%s status=%s code=%s",
            job,
            status,
            (extra.get("failure") or {}).get("code") if isinstance(extra.get("failure"), dict) else None,
        )
        return message

    def failed(code: str, retryable: bool, detail: str | None = None, usage: dict | None = None) -> dict:
        failure = {"code": code, "retryable": retryable}
        if detail:
            failure["detail"] = detail[:500]
        return finish("FAILED", failure=failure, usage=usage)

    try:
        content = fetch(document["bucket"], document["objectKey"])
    except DocumentMissing:
        return failed("DOCUMENT_NOT_FOUND", False)
    if hashlib.sha256(content).hexdigest() != document["sha256"]:
        return failed("CHECKSUM_MISMATCH", False)

    try:
        pdf = read_pdf(content)
    except PdfProblem as problem:
        return failed(problem.code, False, problem.detail)
    if pdf.is_scanned:
        return finish("UNSUPPORTED_SCANNED")

    prompt = render_extraction(pdf.pages)
    try:
        answer = provider.generate_structured(prompt, ExtractedReport)
    except ProviderError as e:
        if e.retryable and not last_attempt:
            raise Retry(e.detail) from e
        return failed(e.code, e.retryable, e.detail)

    usage = {
        "provider": answer.usage.provider,
        "model": answer.usage.model,
        "promptVersion": answer.usage.prompt_version,
        "inputTokens": answer.usage.input_tokens,
        "outputTokens": answer.usage.output_tokens,
        "latencyMs": answer.usage.latency_ms,
    }
    if not answer.value.is_lab_report:
        return failed("NOT_A_LAB_REPORT", False, usage=usage)
    if not answer.value.results:
        return failed("NO_RESULTS_FOUND", False, usage=usage)
    return finish("SUCCEEDED", report=build_report(answer.value, pdf, today()), usage=usage)
