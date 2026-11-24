"""Extraction over HTTP (the Render profile): intake, shared-secret auth, retries and delivery."""

import json

import pytest
from fastapi.testclient import TestClient

from app.config import Settings, get_settings
from app.extraction.http_transport import HttpExtractionWorker
from app.extraction.http_transport import router as extraction_router
from app.extraction.pipeline import DocumentMissing
from app.extraction.providers import FakeProvider, ProviderError
from app.extraction.worker import ExtractionRequest
from app.main import create_app
from tests.extraction_fixtures import expected, fixture_ids, llm_answer_from_ground_truth, pdf_bytes, request_for

SECRET = "s" * 40
PATH = "/internal/v1/queues/document-extraction-requests"
FIXTURE = next(f for f in fixture_ids() if expected(f)["expected"]["status"] == "SUCCEEDED")


class FakeCore:
    def __init__(self, statuses: list[int] | None = None, missing: bool = False) -> None:
        self.statuses = statuses or [202]
        self.delivered: list[dict] = []
        self.missing = missing

    def fetch(self, bucket: str, key: str) -> bytes:
        if self.missing:
            raise DocumentMissing(key)
        return pdf_bytes(FIXTURE)

    def deliver(self, result: dict) -> int:
        self.delivered.append(result)
        return self.statuses.pop(0) if len(self.statuses) > 1 else self.statuses[0]


def good_provider():
    truth = expected(FIXTURE)["expected"]["report"]
    return FakeProvider(lambda p: llm_answer_from_ground_truth(truth))


def worker(core: FakeCore, provider) -> HttpExtractionWorker:
    return HttpExtractionWorker(core, provider, max_attempts=3, sleep=lambda s: None)


def message() -> ExtractionRequest:
    return ExtractionRequest.model_validate(request_for(FIXTURE))


def test_a_result_is_delivered_to_core_api() -> None:
    core = FakeCore()
    assert worker(core, good_provider()).handle(message()) is True
    assert core.delivered[0]["status"] == "SUCCEEDED"
    assert core.delivered[0]["jobId"] == request_for(FIXTURE)["jobId"]


def test_delivery_is_retried_until_core_api_takes_it_and_a_refusal_drops_it() -> None:
    core = FakeCore(statuses=[503, 502, 202])
    assert worker(core, good_provider()).handle(message()) is True
    assert len(core.delivered) == 3

    refused = FakeCore(statuses=[400])
    assert worker(refused, good_provider()).handle(message()) is False
    assert len(refused.delivered) == 1


def test_transient_model_errors_are_retried_then_reported_as_failed() -> None:
    def always_down(prompt):
        raise ProviderError("PROVIDER_OVERLOADED", "overloaded", retryable=True)

    core = FakeCore()
    assert worker(core, FakeProvider(always_down)).handle(message()) is True
    assert core.delivered[0]["status"] == "FAILED"


def test_a_missing_document_is_reported_not_retried() -> None:
    core = FakeCore(missing=True)
    assert worker(core, good_provider()).handle(message()) is True
    assert core.delivered[0]["failure"]["code"] == "DOCUMENT_NOT_FOUND"


@pytest.fixture
def client():
    settings = Settings(HIP_INTERNAL_TOKEN=SECRET, messaging_transport="http")
    app = create_app()
    app.include_router(extraction_router)
    app.dependency_overrides[get_settings] = lambda: settings
    with TestClient(app) as test_client:
        yield app, test_client


def test_intake_needs_the_shared_secret(client) -> None:
    _app, http = client
    body = json.dumps(request_for(FIXTURE))
    assert http.post(PATH, content=body).status_code == 401
    assert http.post(PATH, content=body, headers={"Authorization": "Bearer " + "x" * 40}).status_code == 401


def test_intake_validates_and_queues(client) -> None:
    app, http = client
    auth = {"Authorization": f"Bearer {SECRET}"}
    # Without model credentials nothing is taken, so core-api keeps the message.
    assert http.post(PATH, content=json.dumps(request_for(FIXTURE)), headers=auth).status_code == 503

    app.state.http_worker = HttpExtractionWorker(FakeCore(), good_provider(), max_attempts=1, sleep=lambda s: None)
    assert http.post(PATH, content="{}", headers=auth).status_code == 400
    assert http.post(PATH, content=json.dumps(request_for(FIXTURE)), headers=auth).status_code == 202
    assert app.state.http_worker.pending.qsize() == 1
