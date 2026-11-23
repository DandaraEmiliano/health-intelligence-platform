"""POST /internal/v1/assistant/answers: never exposed publicly; core-api is the only caller."""

from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Request, status

from app.assistant.contract import AnswerRequest, AnswerResponse
from app.assistant.service import AssistantUnavailable, answer
from app.auth import require_service_token

router = APIRouter(prefix="/internal/v1/assistant")


@router.post("/answers", response_model=AnswerResponse, response_model_exclude_none=False)
def create_answer(
    body: AnswerRequest, request: Request, _claims: Annotated[dict, Depends(require_service_token)]
) -> AnswerResponse:
    # Triage runs even without a provider: emergency guidance must never depend on the model.
    provider = getattr(request.app.state, "assistant_provider", None)
    try:
        return answer(body, provider)
    except AssistantUnavailable as e:
        raise HTTPException(
            status.HTTP_503_SERVICE_UNAVAILABLE, detail="AI_UNAVAILABLE", headers={"Retry-After": "30"}
        ) from e
