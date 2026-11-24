"""Extraction over HTTP, for deployments without a queue service (the Render profile).

core-api POSTs each document-extraction-request to `/internal/v1/queues/document-extraction-requests`;
it is accepted (202) into an in-process queue and handled in the background. The PDF is read
from core-api by key (ADR 0003: by key, never by link) and the result is POSTed back to
`/internal/v1/queues/document-extraction-results`, retrying until core-api takes it. Both directions
carry the shared internal token. A request still in memory when the process stops is lost; the
document then stays in processing. Bodies are never logged (T10).
"""

import json
import logging
import queue
import threading
import time
import urllib.error
import urllib.request
from collections.abc import Callable
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Request, Response, status
from pydantic import ValidationError

from app.auth import require_service_token
from app.extraction.pipeline import DocumentMissing, Retry, extract
from app.extraction.providers import LlmProvider
from app.extraction.worker import ExtractionRequest

log = logging.getLogger(__name__)

RESULTS_PATH = "/internal/v1/queues/document-extraction-results"


class CoreApi:
    """The two calls to core-api: read a PDF by key and deliver a result."""

    def __init__(self, base_url: str, token: str, timeout_seconds: float = 120) -> None:
        if not base_url.startswith(("http://", "https://")):
            raise ValueError("AI_CORE_API_URL must be an http(s) URL")
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.timeout = timeout_seconds

    def _request(self, path: str, data: bytes | None = None, headers: dict | None = None):
        request = urllib.request.Request(  # noqa: S310 - the base URL is configuration, checked above
            self.base_url + path,
            data=data,
            method="POST" if data is not None else "GET",
            headers={"Authorization": f"Bearer {self.token}", **(headers or {})},
        )
        return urllib.request.urlopen(request, timeout=self.timeout)  # noqa: S310

    def fetch(self, bucket: str, key: str) -> bytes:
        if not key.startswith("documents/"):
            raise DocumentMissing(key)
        try:
            with self._request("/internal/v1/document-objects/" + key.removeprefix("documents/")) as response:
                return response.read()
        except urllib.error.HTTPError as e:
            if e.code == 404:
                raise DocumentMissing(key) from e
            raise

    def deliver(self, result: dict) -> int:
        """Returns the status; raises on network errors."""
        body = json.dumps(result, ensure_ascii=False).encode()
        headers = {
            "Content-Type": "application/json",
            "X-Event-Type": "DocumentExtractionCompleted",
            "X-Message-Id": result["messageId"],
        }
        try:
            with self._request(RESULTS_PATH, body, headers) as response:
                return response.status
        except urllib.error.HTTPError as e:
            return e.code


class HttpExtractionWorker:
    """One background thread; transient failures are retried here, as a queue would redeliver."""

    def __init__(
        self,
        core: CoreApi,
        provider: LlmProvider,
        max_attempts: int,
        retry_delay_seconds: float = 30,
        sleep: Callable[[float], None] = time.sleep,
    ) -> None:
        self.core = core
        self.provider = provider
        self.max_attempts = max_attempts
        self.retry_delay = retry_delay_seconds
        self.sleep = sleep
        self.pending: queue.Queue[ExtractionRequest | None] = queue.Queue(maxsize=100)
        self._thread: threading.Thread | None = None

    def start(self) -> None:
        self._thread = threading.Thread(target=self._run, name="extraction-http-worker", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self.pending.put(None)
        if self._thread:
            self._thread.join(timeout=25)

    def submit(self, request: ExtractionRequest) -> bool:
        try:
            self.pending.put_nowait(request)
            return True
        except queue.Full:
            return False

    def _run(self) -> None:
        while (request := self.pending.get()) is not None:
            try:
                self.handle(request)
            except Exception as e:  # keep the worker alive
                log.warning("extraction job=%s failed: %s", request.jobId, type(e).__name__)

    def handle(self, request: ExtractionRequest) -> bool:
        """Extracts and delivers one request. Returns whether core-api took the result."""
        result = None
        for attempt in range(1, self.max_attempts + 1):
            try:
                result = extract(
                    request.model_dump(), self.core.fetch, self.provider, last_attempt=attempt >= self.max_attempts
                )
                break
            except Retry:
                log.info("extraction job=%s will be retried (attempt %s)", request.jobId, attempt)
                self.sleep(self.retry_delay * attempt)
        if result is None:
            return False
        for attempt in range(1, 21):
            try:
                code = self.core.deliver(result)
            except OSError as e:
                code = None
                log.info("result for job=%s not delivered: %s", request.jobId, type(e).__name__)
            if code is not None and 200 <= code < 300:
                return True
            if code is not None and 400 <= code < 500:
                log.warning("result for job=%s refused with status %s", request.jobId, code)
                return False
            self.sleep(min(self.retry_delay * attempt, 300))
        return False


router = APIRouter()


@router.post("/internal/v1/queues/document-extraction-requests", status_code=status.HTTP_202_ACCEPTED)
async def receive_extraction_request(
    request: Request, _claims: Annotated[dict, Depends(require_service_token)]
) -> Response:
    worker: HttpExtractionWorker | None = getattr(request.app.state, "http_worker", None)
    if worker is None:
        # No model credentials: core-api keeps the message and tries again later.
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, detail="extraction is not available")
    try:
        message = ExtractionRequest.model_validate_json(await request.body())
    except (ValidationError, ValueError) as e:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, detail="invalid extraction request") from e
    if not worker.submit(message):
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, detail="busy")
    return Response(status_code=status.HTTP_202_ACCEPTED)
