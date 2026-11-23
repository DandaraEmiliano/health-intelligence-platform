"""Checks on what the model wrote (ADR 0004, threat model T05).

Numbers: every number in the answer and explanations must be one the patient can verify in the
fact pack (a displayed value, a reference bound, a collection date) or in their own question; small
counts (up to twelve) are allowed for phrases like "dois exames". Ids: cited facts and glossary
sources must exist. Language: nothing that reads as a prescription, dosage advice or diagnosis.
"""

import re
import unicodedata
from decimal import Decimal, InvalidOperation

from app.assistant.contract import Draft, FactPack

_NUMBER = re.compile(r"(?<![\w.,])-?\d{1,3}(?:\.\d{3})+(?:,\d+)?(?![\w])|(?<![\w.,])-?\d+(?:[.,]\d+)?(?![\w])")

_PRESCRIBING = [
    r"\b(tome|tomar|use|usar|aumente|diminua|reduza|suspenda|pare de tomar|comece a tomar|inicie)\b[^.]{0,60}"
    r"\b(\d+\s?(mg|mcg|ui|ml|g)\b|comprimido|capsula|gotas|dose|remedio|medicamento|suplemento)",
    r"\b(recomendo|indico|sugiro)\b[^.]{0,40}\b(remedio|medicamento|suplemento|tratamento)",
    r"\bprescrev",
]
_DIAGNOSING = [
    r"\bvoce (tem|esta com|possui|sofre de) (diabetes|pre-?diabetes|hipotireoidismo|hipertireoidismo|anemia|cancer|"
    r"leucemia|hepatite|hiv|insuficiencia renal|doenca renal|cirrose|infeccao|dislipidemia)",
    r"\bseu diagnostico (e|seria)\b",
    r"\b(confirma|confirmam|indica|indicam) que voce tem\b",
]


def _plain(text: str) -> str:
    stripped = unicodedata.normalize("NFD", text)
    return "".join(c for c in stripped if unicodedata.category(c) != "Mn").lower()


def _value(token: str) -> Decimal | None:
    text = token
    if re.fullmatch(r"-?\d{1,3}(?:\.\d{3})+(?:,\d+)?", text):
        text = text.replace(".", "").replace(",", ".")
    else:
        text = text.replace(",", ".")
    try:
        return Decimal(text)
    except InvalidOperation:
        return None


def numbers_in(text: str) -> list[Decimal]:
    return [v for v in (_value(m.group(0)) for m in _NUMBER.finditer(text)) if v is not None]


def allowed_numbers(pack: FactPack, question: str) -> set[Decimal]:
    allowed: set[Decimal] = set(numbers_in(question))
    allowed.update(Decimal(n) for n in range(13))
    for fact in pack.results:
        allowed.update(numbers_in(fact.displayValue))
        for raw in (fact.valueNumeric, fact.low, fact.high):
            if raw is not None:
                allowed.add(Decimal(raw))
        if fact.referenceRange:
            allowed.update(numbers_in(fact.referenceRange))
        if fact.collectedAt:
            year, month, day = fact.collectedAt.split("-")
            allowed.update({Decimal(year), Decimal(month), Decimal(day), Decimal(year[2:])})
    for medication in pack.medications:
        for text in (medication.dosageText, medication.frequencyText):
            if text:
                allowed.update(numbers_in(text))
    return {a.normalize() for a in allowed}


def violations(draft: Draft, pack: FactPack, question: str, glossary_ids: set[str]) -> list[str]:
    """Reasons the draft cannot be shown, in plain English for the regeneration prompt. Empty if fine."""
    problems: list[str] = []
    allowed = allowed_numbers(pack, question)
    texts = [draft.answer, *(e.text for e in draft.explanations)]
    for text in texts:
        for number in numbers_in(text):
            if number.normalize() not in allowed:
                problems.append(f"the number {number} is not in the facts")
    for text in (e.text for e in draft.explanations):
        if any(n.normalize() not in {Decimal(i) for i in range(13)} for n in numbers_in(text)):
            problems.append("explanations must not contain patient numbers")
            break
    fact_ids = {f.factId for f in pack.results}
    problems += [f"unknown fact_id {i}" for i in draft.cited_fact_ids if i not in fact_ids]
    problems += [
        f"unknown glossary id {g}" for e in draft.explanations for g in e.glossary_ids if g not in glossary_ids
    ]
    plain = _plain(" ".join(texts + draft.suggested_questions_for_doctor))
    if any(re.search(p, plain) for p in _PRESCRIBING):
        problems.append("the text reads as prescribing or dosage advice")
    if any(re.search(p, plain) for p in _DIAGNOSING):
        problems.append("the text states a diagnosis")
    return problems
