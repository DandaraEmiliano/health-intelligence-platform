"""LLM providers behind one thin interface (ADR 0008): structured output is mandatory."""

import json
import time
from collections.abc import Callable
from dataclasses import dataclass
from typing import Protocol, TypeVar

import anthropic
from pydantic import BaseModel, ValidationError

from app.extraction.prompts import RenderedPrompt

T = TypeVar("T", bound=BaseModel)


@dataclass(frozen=True)
class Usage:
    provider: str
    model: str
    prompt_version: str
    input_tokens: int = 0
    output_tokens: int = 0
    latency_ms: int = 0


@dataclass(frozen=True)
class StructuredResult[T]:
    value: T
    usage: Usage


class ProviderError(Exception):
    """The provider could not produce an answer. `retryable` says whether trying later may help."""

    def __init__(self, code: str, detail: str, retryable: bool) -> None:
        super().__init__(detail)
        self.code = code
        self.detail = detail
        self.retryable = retryable


class LlmProvider(Protocol):
    def generate_structured(self, prompt: RenderedPrompt, output_model: type[T]) -> StructuredResult[T]: ...


def _strict_schema(model: type[BaseModel]) -> dict:
    """Pydantic schema as structured outputs accept it: no titles/descriptions noise, closed objects."""
    schema = model.model_json_schema()

    def clean(node: object) -> None:
        if isinstance(node, dict):
            node.pop("title", None)
            if node.get("type") == "object":
                node["additionalProperties"] = False
                node.setdefault("required", list(node.get("properties", {})))
            for value in node.values():
                clean(value)
        elif isinstance(node, list):
            for value in node:
                clean(value)

    clean(schema)
    return schema


class AnthropicProvider:
    """Claude through the official SDK, with JSON structured output validated by Pydantic.

    Uses streaming (long outputs), adaptive thinking (the default on this model), an explicit
    effort, prompt caching on the frozen system prompt, and the server-side refusal fallback.
    Credentials come from the environment (ANTHROPIC_API_KEY or an `ant auth login` profile).
    """

    FALLBACK_BETA = "server-side-fallback-2026-07-01"

    def __init__(
        self,
        model: str,
        effort: str,
        max_tokens: int = 64000,
        timeout_seconds: float = 300.0,
        client: anthropic.Anthropic | None = None,
    ) -> None:
        self.model = model
        self.effort = effort
        self.max_tokens = max_tokens
        self.client = client or anthropic.Anthropic(timeout=timeout_seconds, max_retries=2)

    def generate_structured(self, prompt: RenderedPrompt, output_model: type[T]) -> StructuredResult[T]:
        started = time.monotonic()
        last_error: str | None = None
        for _attempt in range(2):  # ADR 0008: one retry with the validation error, then fail
            user = (
                prompt.user
                if last_error is None
                else (f"{prompt.user}\n\nYour previous answer did not match the schema: {last_error[:1000]}")
            )
            message = self._call(prompt.system, user, output_model)
            if message.stop_reason == "refusal":
                raise ProviderError("LLM_PROVIDER_ERROR", "model declined the request", retryable=False)
            if message.stop_reason == "max_tokens":
                raise ProviderError("OUTPUT_SCHEMA_VIOLATION", "output truncated at max_tokens", retryable=False)
            text = next((block.text for block in message.content if block.type == "text"), "")
            try:
                value = output_model.model_validate(json.loads(text))
            except (json.JSONDecodeError, ValidationError) as e:
                last_error = str(e)
                continue
            usage = Usage(
                provider="anthropic",
                model=message.model,
                prompt_version=prompt.version,
                input_tokens=(message.usage.input_tokens or 0)
                + (message.usage.cache_read_input_tokens or 0)
                + (message.usage.cache_creation_input_tokens or 0),
                output_tokens=message.usage.output_tokens or 0,
                latency_ms=int((time.monotonic() - started) * 1000),
            )
            return StructuredResult(value=value, usage=usage)
        raise ProviderError("OUTPUT_SCHEMA_VIOLATION", "output did not match the schema twice", retryable=False)

    def _call(self, system: str, user: str, output_model: type[BaseModel]):
        try:
            with self.client.beta.messages.stream(
                model=self.model,
                max_tokens=self.max_tokens,
                betas=[self.FALLBACK_BETA],
                fallbacks="default",
                system=[{"type": "text", "text": system, "cache_control": {"type": "ephemeral"}}],
                messages=[{"role": "user", "content": user}],
                output_config={
                    "effort": self.effort,
                    "format": {"type": "json_schema", "schema": _strict_schema(output_model)},
                },
            ) as stream:
                return stream.get_final_message()
        except (anthropic.AuthenticationError, anthropic.PermissionDeniedError) as e:
            # Fixed by configuration, not by the document: keep the job for when credentials work.
            raise ProviderError("LLM_PROVIDER_ERROR", type(e).__name__, retryable=True) from e
        except (anthropic.RateLimitError, anthropic.InternalServerError, anthropic.APIConnectionError) as e:
            # The SDK already retried; let the queue redeliver later.
            raise ProviderError("LLM_PROVIDER_ERROR", type(e).__name__, retryable=True) from e
        except anthropic.APIStatusError as e:
            raise ProviderError("LLM_PROVIDER_ERROR", f"HTTP {e.status_code}", retryable=e.status_code >= 500) from e


class FakeProvider:
    """Deterministic provider for tests (ADR 0008): answers from a function of the prompt."""

    def __init__(self, answer: Callable[[RenderedPrompt], BaseModel], model: str = "fake") -> None:
        self.answer = answer
        self.model = model
        self.calls: list[RenderedPrompt] = []

    def generate_structured(self, prompt: RenderedPrompt, output_model: type[T]) -> StructuredResult[T]:
        self.calls.append(prompt)
        value = self.answer(prompt)
        if isinstance(value, Exception):
            raise value
        return StructuredResult(
            value=output_model.model_validate(value.model_dump()),
            usage=Usage(provider="fake", model=self.model, prompt_version=prompt.version),
        )
