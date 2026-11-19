"""AnthropicProvider against a stand-in for the SDK client: request shape and output handling."""

import json
from contextlib import contextmanager
from types import SimpleNamespace

import pytest

from app.extraction.model import ExtractedReport
from app.extraction.prompts import RenderedPrompt
from app.extraction.providers import AnthropicProvider, ProviderError

PROMPT = RenderedPrompt(version="extraction/v1", system="SYSTEM", user="<document>...</document>")
VALID = {
    "is_lab_report": True,
    "laboratory_name": None,
    "collected_at_raw": None,
    "reported_at_raw": None,
    "results": [],
}


def message(text: str, stop_reason: str = "end_turn"):
    return SimpleNamespace(
        stop_reason=stop_reason,
        model="claude-opus-5-5",
        content=[SimpleNamespace(type="thinking", thinking=""), SimpleNamespace(type="text", text=text)],
        usage=SimpleNamespace(
            input_tokens=10, output_tokens=20, cache_read_input_tokens=5, cache_creation_input_tokens=0
        ),
    )


class FakeClient:
    def __init__(self, *answers) -> None:
        self.answers = list(answers)
        self.requests: list[dict] = []
        self.beta = SimpleNamespace(messages=SimpleNamespace(stream=self._stream))

    @contextmanager
    def _stream(self, **kwargs):
        self.requests.append(kwargs)
        yield SimpleNamespace(get_final_message=lambda: self.answers.pop(0))


def test_request_uses_structured_output_effort_caching_and_fallback() -> None:
    client = FakeClient(message(json.dumps(VALID)))

    result = AnthropicProvider("claude-opus-5-5", "high", client=client).generate_structured(PROMPT, ExtractedReport)

    request = client.requests[0]
    assert request["model"] == "claude-opus-5-5"
    assert request["output_config"]["effort"] == "high"
    schema = request["output_config"]["format"]["schema"]
    assert request["output_config"]["format"]["type"] == "json_schema"
    assert schema["additionalProperties"] is False
    assert request["system"][0]["cache_control"] == {"type": "ephemeral"}
    assert request["fallbacks"] == "default" and request["betas"] == ["server-side-fallback-2026-07-01"]
    assert "thinking" not in request  # adaptive by default on this model
    assert result.value.is_lab_report is True
    assert (result.usage.input_tokens, result.usage.output_tokens, result.usage.prompt_version) == (
        15,
        20,
        "extraction/v1",
    )


def test_invalid_output_is_retried_once_with_the_error() -> None:
    client = FakeClient(message("{not json"), message(json.dumps(VALID)))

    AnthropicProvider("claude-opus-5-5", "high", client=client).generate_structured(PROMPT, ExtractedReport)

    assert len(client.requests) == 2
    assert "did not match the schema" in client.requests[1]["messages"][0]["content"]


def test_twice_invalid_output_fails_with_schema_violation() -> None:
    client = FakeClient(message("{}"), message("{}"))

    with pytest.raises(ProviderError) as error:
        AnthropicProvider("claude-opus-5-5", "high", client=client).generate_structured(PROMPT, ExtractedReport)

    assert (error.value.code, error.value.retryable) == ("OUTPUT_SCHEMA_VIOLATION", False)


def test_refusal_and_truncation_are_not_retried() -> None:
    for stop_reason, code in [("refusal", "LLM_PROVIDER_ERROR"), ("max_tokens", "OUTPUT_SCHEMA_VIOLATION")]:
        client = FakeClient(message("", stop_reason))
        with pytest.raises(ProviderError) as error:
            AnthropicProvider("claude-opus-5-5", "high", client=client).generate_structured(PROMPT, ExtractedReport)
        assert error.value.code == code and len(client.requests) == 1
