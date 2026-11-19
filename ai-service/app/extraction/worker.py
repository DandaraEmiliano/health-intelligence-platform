"""Consumes document-extraction-requests and publishes document-extraction-results.

At-least-once on both sides: a message is deleted only after its result was published, and
the core ignores duplicate results by jobId (ADR 0003). Bodies are never logged (T10).
"""

import json
import logging
import threading
from collections.abc import Callable

from pydantic import BaseModel, ConfigDict, Field, ValidationError

from app.extraction.pipeline import Retry, extract
from app.extraction.providers import LlmProvider

log = logging.getLogger(__name__)


class _Document(BaseModel):
    model_config = ConfigDict(extra="forbid")
    bucket: str
    objectKey: str = Field(pattern=r"^documents/[0-9a-f-]{36}$")
    sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    contentType: str
    sizeBytes: int


class ExtractionRequest(BaseModel):
    """document-extraction-request.v1, validated on read (ADR 0003)."""

    model_config = ConfigDict(extra="forbid")
    schemaVersion: str = Field(pattern=r"^1\.0$")
    messageId: str
    jobId: str
    documentId: str
    patientId: str
    extractorVersion: str = Field(pattern=r"^[0-9]+\.[0-9]+\.[0-9]+$")
    document: _Document
    requestedAt: str
    correlationId: str | None = None


class ExtractionWorker:
    def __init__(
        self,
        sqs,
        requests_queue: str,
        results_queue: str,
        fetch: Callable[[str, str], bytes],
        provider: LlmProvider,
        max_receives: int,
    ) -> None:
        self.sqs = sqs
        self.requests_url = sqs.get_queue_url(QueueName=requests_queue)["QueueUrl"]
        self.results_url = sqs.get_queue_url(QueueName=results_queue)["QueueUrl"]
        self.fetch = fetch
        self.provider = provider
        self.max_receives = max_receives
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def start(self) -> None:
        self._thread = threading.Thread(target=self._run, name="extraction-worker", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        if self._thread:
            self._thread.join(timeout=25)

    def _run(self) -> None:
        while not self._stop.is_set():
            try:
                self.poll_once(wait_seconds=10)
            except Exception as e:  # keep the worker alive; the message stays on the queue
                log.warning("extraction poll failed: %s", type(e).__name__)
                self._stop.wait(5)

    def poll_once(self, wait_seconds: int = 0) -> int:
        """Handles at most one message. Returns how many results were published (0 or 1)."""
        response = self.sqs.receive_message(
            QueueUrl=self.requests_url,
            MaxNumberOfMessages=1,
            WaitTimeSeconds=wait_seconds,
            MessageSystemAttributeNames=["ApproximateReceiveCount"],
        )
        published = 0
        for message in response.get("Messages", []):
            published += self.handle(message)
        return published

    def handle(self, message: dict) -> int:
        receipt = message["ReceiptHandle"]
        try:
            request = ExtractionRequest.model_validate_json(message["Body"])
        except (ValidationError, json.JSONDecodeError):
            # A malformed request can never succeed; drop it (the core can resend a new job).
            log.warning("dropping invalid extraction request message=%s", message.get("MessageId"))
            self.sqs.delete_message(QueueUrl=self.requests_url, ReceiptHandle=receipt)
            return 0
        receives = int(message.get("Attributes", {}).get("ApproximateReceiveCount", "1"))
        try:
            result = extract(
                request.model_dump(), self.fetch, self.provider, last_attempt=receives >= self.max_receives
            )
        except Retry:
            log.info("extraction job=%s will be retried (receive %s)", request.jobId, receives)
            return 0
        self.sqs.send_message(
            QueueUrl=self.results_url,
            MessageBody=json.dumps(result, ensure_ascii=False),
            MessageAttributes={
                "eventType": {"DataType": "String", "StringValue": "DocumentExtractionCompleted"},
                "messageId": {"DataType": "String", "StringValue": result["messageId"]},
            },
        )
        self.sqs.delete_message(QueueUrl=self.requests_url, ReceiptHandle=receipt)
        return 1
