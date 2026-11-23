"""Pydantic mirror of contracts/internal/assistant-answer-*.v1.schema.json."""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

DECIMAL = r"^-?(0|[1-9][0-9]*)(\.[0-9]+)?$"


class _Closed(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Fact(_Closed):
    factId: str
    analyteCode: str | None = None
    label: str = Field(max_length=200)
    collectedAt: str | None = None
    kind: Literal["NUMERIC", "TEXT"]
    displayValue: str = Field(max_length=200)
    valueNumeric: str | None = Field(default=None, pattern=DECIMAL)
    comparator: Literal["<", "<=", ">", ">="] | None = None
    unit: str | None = None
    referenceRange: str | None = None
    low: str | None = Field(default=None, pattern=DECIMAL)
    high: str | None = Field(default=None, pattern=DECIMAL)
    flag: Literal["HIGH", "LOW", "ABNORMAL", "CRITICAL", "IN_RANGE"] | None = None
    flagSource: Literal["LABORATORY", "COMPUTED"] | None = None
    laboratoryName: str | None = None


class Allergy(_Closed):
    substance: str
    type: str | None = None
    severity: str | None = None


class Medication(_Closed):
    name: str
    dosageText: str | None = None
    frequencyText: str | None = None
    active: bool


class Condition(_Closed):
    description: str
    status: str | None = None


class PatientInfo(_Closed):
    ageYears: int | None = None
    biologicalSex: Literal["FEMALE", "MALE", "INTERSEX", "UNDISCLOSED"] | None = None


class FactPack(_Closed):
    patient: PatientInfo | None = None
    results: list[Fact] = Field(max_length=300)
    allergies: list[Allergy] = Field(max_length=200)
    medications: list[Medication] = Field(max_length=200)
    conditions: list[Condition] = Field(max_length=200)


class Turn(_Closed):
    role: Literal["USER", "ASSISTANT"]
    content: str = Field(max_length=4000)


class AnswerRequest(_Closed):
    schemaVersion: Literal["1.0"]
    requestId: str
    patientId: str
    question: str = Field(min_length=1, max_length=2000)
    history: list[Turn] = Field(max_length=10)
    factPack: FactPack


ReasonCode = Literal[
    "DIAGNOSIS_REQUEST", "PRESCRIPTION_REQUEST", "DOSAGE_CHANGE_REQUEST", "OUT_OF_SCOPE", "EMERGENCY_SIGNS"
]
Urgency = Literal["NONE", "ROUTINE", "SOON", "EMERGENCY"]


class Source(_Closed):
    type: Literal["GLOSSARY"] = "GLOSSARY"
    id: str


class Explanation(_Closed):
    text: str
    sources: list[Source]


class Care(_Closed):
    recommended: bool
    urgency: Urgency


class Safety(_Closed):
    refused: bool
    reasonCode: ReasonCode | None


class Usage(_Closed):
    provider: str
    model: str
    inputTokens: int = 0
    outputTokens: int = 0
    latencyMs: int = 0


class AnswerResponse(_Closed):
    schemaVersion: Literal["1.0"] = "1.0"
    requestId: str
    outcome: Literal["PASSED", "REGENERATED", "REFUSED", "EMERGENCY_GUIDANCE", "FAILED"]
    answer: str
    citedFactIds: list[str]
    explanations: list[Explanation]
    uncertainty: Literal["LOW", "MEDIUM", "HIGH"]
    insufficientData: bool
    seekProfessionalCare: Care
    suggestedQuestionsForDoctor: list[str]
    safety: Safety
    promptVersion: str
    usage: Usage | None = None


# What the LLM returns (snake_case, strict schema for structured outputs).
class DraftExplanation(_Closed):
    text: str
    glossary_ids: list[str]


class DraftCare(_Closed):
    recommended: bool
    urgency: Urgency


class Draft(_Closed):
    answer: str = Field(description="The answer to the patient, pt-BR.")
    cited_fact_ids: list[str] = Field(description="fact_id of every fact mentioned in the answer.")
    explanations: list[DraftExplanation] = Field(description="General explanations, each with the glossary ids used.")
    uncertainty: Literal["LOW", "MEDIUM", "HIGH"]
    insufficient_data: bool
    seek_professional_care: DraftCare
    suggested_questions_for_doctor: list[str]
    refused: bool
    refusal_reason: ReasonCode | None
