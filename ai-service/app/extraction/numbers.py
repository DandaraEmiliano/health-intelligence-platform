"""Brazilian-Portuguese number and range parsing (ADR 0007: parse only when unambiguous)."""

import re
from dataclasses import dataclass

_COMPARATORS = [
    (re.compile(r"^(?:<=|≤|inferior ou igual a|menor ou igual a)\s*", re.I), "<="),
    (re.compile(r"^(?:>=|≥|superior ou igual a|maior ou igual a)\s*", re.I), ">="),
    (re.compile(r"^(?:<|inferior a|menor que|abaixo de)\s*", re.I), "<"),
    (re.compile(r"^(?:>|superior a|maior que|acima de)\s*", re.I), ">"),
]
_NUMBER = re.compile(r"^-?\d{1,3}(?:\.\d{3})+(?:,\d+)?$|^-?\d+(?:,\d+)?$|^-?\d+\.\d{1,2}$")


def decimal_string(printed: str) -> str | None:
    """'1,25' -> '1.25', '245.000' -> '245000', '1.234,5' -> '1234.5'; None if not a plain number.

    A dot followed by exactly three digits is a thousands separator (pt-BR); a dot followed by one
    or two digits is read as a decimal point, since some laboratories print it that way.
    """
    text = printed.strip()
    if not _NUMBER.match(text):
        return None
    if "," in text:
        return text.replace(".", "").replace(",", ".")
    if re.search(r"\.\d{3}(?:\.|$)", text):
        return text.replace(".", "")
    return text


@dataclass(frozen=True)
class Measurement:
    kind: str
    value_numeric: str | None = None
    comparator: str | None = None
    value_text: str | None = None


def parse_measurement(raw_value: str) -> Measurement:
    text = raw_value.strip()
    comparator = None
    for pattern, symbol in _COMPARATORS:
        match = pattern.match(text)
        if match:
            comparator = symbol
            text = text[match.end() :]
            break
    number = decimal_string(text)
    if number is None:
        return Measurement(kind="TEXT", value_text=raw_value.strip())
    return Measurement(kind="NUMERIC", value_numeric=number, comparator=comparator)


# Population qualifiers: a range prefixed by one of these applies to that population only.
_POPULATIONS = re.compile(
    r"^(homens|mulheres|masculino|feminino|adultos|crianças|criancas|gestantes|idosos|[FM])\s*:\s*", re.I
)
_CATEGORY = re.compile(r"^[A-Za-zÀ-ÿ ]{2,40}:\s*")  # e.g. "Desejável:", "Suficiência:"
_SEPARATED = re.compile(r"\s*(?:\||/|;)\s*")
_INTERVAL = re.compile(r"^(?P<low>[\d.,]+)\s*(?:-|a|até|\u2013)\s*(?P<high>[\d.,]+)\b")
_UPPER = re.compile(r"^(?:<=?|≤|até|inferior a|menor que|abaixo de)\s*(?P<v>[\d.,]+)", re.I)
_LOWER = re.compile(r"^(?:>=?|≥|acima de|superior a|maior que)\s*(?P<v>[\d.,]+)", re.I)


@dataclass(frozen=True)
class ReferenceRange:
    raw_text: str | None
    low: str | None = None
    high: str | None = None
    applies_to: str | None = None
    multiple: bool = False
    unparseable: bool = False


def parse_range(raw_text: str | None) -> ReferenceRange:
    if raw_text is None or not raw_text.strip():
        return ReferenceRange(raw_text=None)
    text = raw_text.strip()
    parts = [p for p in _SEPARATED.split(text) if p]
    populations = [p for p in parts if _POPULATIONS.match(p)]
    if len(populations) > 1:
        # Several populations printed: picking one would be a guess (ADR 0007).
        return ReferenceRange(raw_text=text, multiple=True)

    body = text
    applies_to = None
    population = _POPULATIONS.match(body)
    if population:
        applies_to = population.group(1)
        body = body[population.end() :]
    else:
        category = _CATEGORY.match(body)
        if category:
            body = body[category.end() :]

    interval = _INTERVAL.match(body)
    if interval:
        low, high = decimal_string(interval.group("low")), decimal_string(interval.group("high"))
        if low is not None and high is not None:
            return ReferenceRange(raw_text=text, low=low, high=high, applies_to=applies_to)
    upper = _UPPER.match(body)
    if upper and decimal_string(upper.group("v")):
        return ReferenceRange(raw_text=text, high=decimal_string(upper.group("v")), applies_to=applies_to)
    lower = _LOWER.match(body)
    if lower and decimal_string(lower.group("v")):
        return ReferenceRange(raw_text=text, low=decimal_string(lower.group("v")), applies_to=applies_to)
    return ReferenceRange(raw_text=text, applies_to=applies_to, unparseable=True)
