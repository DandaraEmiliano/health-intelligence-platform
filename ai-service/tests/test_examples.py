"""The demo's example reports are read without a model; every other document still needs one."""

import hashlib
import json
from datetime import date
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.config import Settings, get_settings
from app.extraction import examples
from app.extraction.http_transport import HttpExtractionWorker
from app.extraction.http_transport import router as extraction_router
from app.extraction.pdf import read_pdf
from app.extraction.pipeline import extract
from app.extraction.providers import UnavailableProvider
from app.extraction.report import build_report
from app.main import create_app
from tests.extraction_fixtures import fixture_ids, request_for

EXAMPLES = sorted((Path(__file__).resolve().parents[2] / "web" / "public" / "exemplos").glob("*.pdf"))
SECRET = "s" * 40
PATH = "/internal/v1/queues/document-extraction-requests"


def _request(content: bytes) -> dict:
    request = request_for(fixture_ids()[0])
    request["document"].update(sha256=hashlib.sha256(content).hexdigest(), sizeBytes=len(content))
    return request


def test_every_published_example_has_a_stored_reading_and_nothing_else_does() -> None:
    assert len(EXAMPLES) == 3
    stored = json.loads(Path(examples.__file__).with_name("examples.json").read_text(encoding="utf-8"))
    published = {hashlib.sha256(p.read_bytes()).hexdigest(): p.name for p in EXAMPLES}
    assert {sha: entry["file"] for sha, entry in stored.items()} == published


@pytest.mark.parametrize("path", EXAMPLES, ids=lambda p: p.name)
def test_the_stored_reading_matches_the_pdf_text(path: Path) -> None:
    content = path.read_bytes()
    answer = examples.lookup(hashlib.sha256(content).hexdigest())
    assert answer is not None
    report = build_report(answer.value, read_pdf(content), date(2026, 11, 24))
    # Same validators as a model answer: every excerpt and value is on its page, ranges parse.
    assert report["warnings"] == []
    assert [r for r in report["results"] if r.get("warnings")] == []
    assert len(report["results"]) == 15
    assert report["collectedAt"] == path.stem.removeprefix("laudo-exemplo-")


def test_an_example_is_extracted_without_calling_a_model() -> None:
    content = EXAMPLES[0].read_bytes()
    result = extract(_request(content), lambda bucket, key: content, UnavailableProvider())
    assert result["status"] == "SUCCEEDED"
    assert result["usage"]["provider"] == "none"
    assert result["usage"]["inputTokens"] == 0


def test_a_changed_byte_is_no_longer_an_example() -> None:
    content = bytearray(EXAMPLES[0].read_bytes())
    content[-10] ^= 1
    assert examples.lookup(hashlib.sha256(bytes(content)).hexdigest()) is None


@pytest.fixture
def client():
    settings = Settings(HIP_INTERNAL_TOKEN=SECRET, messaging_transport="http")
    app = create_app()
    app.include_router(extraction_router)
    app.dependency_overrides[get_settings] = lambda: settings
    with TestClient(app) as test_client:
        yield app, test_client


def test_without_a_model_only_examples_are_taken(client) -> None:
    app, http = client
    auth = {"Authorization": f"Bearer {SECRET}"}
    app.state.http_worker = HttpExtractionWorker(None, UnavailableProvider(), max_attempts=1, sleep=lambda s: None)
    app.state.model_available = False

    other = request_for(fixture_ids()[0])
    assert http.post(PATH, content=json.dumps(other), headers=auth).status_code == 503
    example = _request(EXAMPLES[1].read_bytes())
    assert http.post(PATH, content=json.dumps(example), headers=auth).status_code == 202
    assert app.state.http_worker.pending.qsize() == 1
