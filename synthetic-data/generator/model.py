"""Builds the ground truth for one synthetic report: values, printed strings and expected extraction."""

import hashlib
import random
from dataclasses import dataclass, field
from datetime import date
from decimal import ROUND_HALF_UP, Decimal

from generator.catalog import ANALYTES, Analyte, Patient, Range

LAB_NAMES = {
    "alfa": "Laboratório Sintético Alfa",
    "beta": "Beta Diagnósticos Sintéticos",
    "gama": "Gama Análises Clínicas (Fictício)",
    "delta": "Delta Medicina Laboratorial - Unidade Teste",
}

SEX_LABEL = {"F": "Mulheres", "M": "Homens"}


def fmt_number(value: Decimal, decimals: int, thousands: bool = False) -> str:
    """pt-BR formatting: comma decimal separator, optional dot thousands separator."""
    quantized = value.quantize(Decimal(1).scaleb(-decimals), rounding=ROUND_HALF_UP)
    sign = "-" if quantized < 0 else ""
    integer, _, fraction = f"{abs(quantized):f}".partition(".")
    if thousands:
        integer = f"{int(integer):,}".replace(",", ".")
    return sign + integer + ("," + fraction if fraction else "")


def plain_decimal(value: Decimal, decimals: int) -> str:
    """Schema decimal string: dot separator, no thousands separator."""
    return f"{value.quantize(Decimal(1).scaleb(-decimals), rounding=ROUND_HALF_UP):f}"


def range_number(value: float, decimals: int) -> tuple[str, str]:
    """(printed pt-BR, schema decimal) for a range limit. Integers are printed without decimals."""
    d = Decimal(str(value))
    places = 0 if d == d.to_integral_value() else decimals
    thousands = abs(d) >= 10000
    return fmt_number(d, places, thousands), plain_decimal(d, places)


@dataclass
class ExpectedResult:
    sequence: int
    raw_name: str
    kind: str
    raw_value: str
    value_numeric: str | None
    comparator: str | None
    value_text: str | None
    unit_raw: str | None
    range_raw: str | None
    low: str | None
    high: str | None
    applies_to: str | None
    lab_flag: str | None
    method: str | None
    page: int = 1
    excerpt: str = ""
    warnings: list[dict] = field(default_factory=list)
    analyte_code: str = ""
    printed_flag: str = ""

    def to_schema(self) -> dict:
        measurement: dict = {"kind": self.kind, "rawValue": self.raw_value}
        if self.kind == "NUMERIC":
            measurement["valueNumeric"] = self.value_numeric
            if self.comparator:
                measurement["comparator"] = self.comparator
        else:
            measurement["valueText"] = self.value_text
        result = {
            "sequence": self.sequence,
            "rawName": self.raw_name,
            "measurement": measurement,
            "unit": {"raw": self.unit_raw},
            "referenceRange": {
                "rawText": self.range_raw,
                "low": self.low,
                "high": self.high,
                "appliesTo": self.applies_to,
            },
            "labFlag": self.lab_flag,
            "method": self.method,
            "source": {"page": self.page, "excerpt": self.excerpt},
        }
        if self.warnings:
            result["warnings"] = self.warnings
        return result


@dataclass
class ReportSpec:
    fixture_id: str
    patient: Patient
    lab: str
    collected_at: date | None
    collected_at_raw: str | None
    reported_at: date
    reported_at_raw: str
    analyte_codes: list[str]
    observation: str | None = None
    tags: list[str] = field(default_factory=list)
    results: list[ExpectedResult] = field(default_factory=list)
    page_count: int = 1


def _seeded_random(*parts: object) -> random.Random:
    digest = hashlib.sha256("|".join(map(str, parts)).encode()).hexdigest()
    return random.Random(int(digest[:16], 16))


def _true_value(patient: Patient, analyte: Analyte, when: date, origin: date) -> float:
    years = (when - origin).days / 365.25
    drift = patient.drift.get(analyte.code, 0.0)
    rng = _seeded_random(patient.synthetic_id, analyte.code, when.isoformat())
    value = analyte.baseline * (1 + drift) ** years + rng.gauss(0, analyte.spread * 0.35)
    return max(value, analyte.baseline * 0.05)


def _lab_variant(analyte: Analyte, lab: str) -> tuple[str | None, int, float, Range, bool]:
    """Lab-specific unit, decimals, scale, range and thousands formatting."""
    if lab == "delta" and analyte.code == "GLUCOSE":
        return "mmol/L", 1, 1 / 18.0, Range("between", 3.9, 5.5), False
    if lab == "beta" and analyte.code == "PLT":
        return "mil/mm³", 0, 1 / 1000, Range("between", 150, 450), False
    return analyte.unit, analyte.decimals, 1.0, analyte.range, analyte.thousands


def _range_text(rng: Range, lab: str, sex: str, decimals: int, unit: str | None) -> tuple:
    """Returns (raw_text, low, high, applies_to, warnings, patient_low, patient_high)."""
    unit_suffix = f" {unit}" if (lab == "beta" and unit) else ""

    def label(text: str) -> str:
        return f"{rng.label}: {text}" if rng.label else text

    if rng.kind == "text":
        return rng.text, None, None, None, [{"code": "RANGE_UNPARSEABLE"}], None, None
    if rng.kind == "none":
        return None, None, None, None, [], None, None

    lt_word = {"alfa": "Inferior a ", "beta": "< ", "gama": "até ", "delta": "< "}[lab]
    gt_word = {"alfa": "Superior a ", "beta": "> ", "gama": "acima de ", "delta": "> "}[lab]
    between = {"alfa": "{} a {}", "beta": "{} - {}", "gama": "{} a {}", "delta": "{} a {}"}[lab]

    if rng.by_sex:
        f_low, f_high = (range_number(v, decimals) for v in rng.by_sex["F"])
        m_low, m_high = (range_number(v, decimals) for v in rng.by_sex["M"])
        p_low, p_high = rng.by_sex[sex]
        if lab in ("alfa", "gama"):  # only the patient's group is printed
            lo, hi = (f_low, f_high) if sex == "F" else (m_low, m_high)
            text = f"{SEX_LABEL[sex]}: " + between.format(lo[0], hi[0]) + unit_suffix
            return text, lo[1], hi[1], SEX_LABEL[sex], [], p_low, p_high
        if lab == "beta":
            text = (
                f"Mulheres: {between.format(f_low[0], f_high[0])}{unit_suffix} | "
                f"Homens: {between.format(m_low[0], m_high[0])}{unit_suffix}"
            )
        else:
            text = f"F: {between.format(f_low[0], f_high[0])} / M: {between.format(m_low[0], m_high[0])}"
        return text, None, None, None, [{"code": "MULTIPLE_RANGES"}], p_low, p_high

    if rng.kind == "between":
        lo, hi = range_number(rng.low, decimals), range_number(rng.high, decimals)
        return label(between.format(lo[0], hi[0]) + unit_suffix), lo[1], hi[1], None, [], rng.low, rng.high
    if rng.kind == "lt":
        hi = range_number(rng.high, decimals)
        return label(lt_word + hi[0] + unit_suffix), None, hi[1], None, [], None, rng.high
    lo = range_number(rng.low, decimals)
    return label(gt_word + lo[0] + unit_suffix), lo[1], None, None, [], rng.low, None


def _flag(rng: Range, number: Decimal, low: float | None, high: float | None) -> str | None:
    """Abnormality as the laboratory would print it, against the patient's applicable limits."""
    if rng.kind == "lt":
        return "HIGH" if number >= Decimal(str(high)) else None
    if rng.kind == "gt":
        return "LOW" if number <= Decimal(str(low)) else None
    if high is not None and number > Decimal(str(high)):
        return "HIGH"
    if low is not None and number < Decimal(str(low)):
        return "LOW"
    return None


def build_results(spec: ReportSpec, origin: date) -> None:
    sex = spec.patient.sex
    when = spec.collected_at or spec.reported_at
    for sequence, code in enumerate(spec.analyte_codes, start=1):
        analyte = ANALYTES[code]
        name = analyte.names.get(spec.lab, analyte.name)
        method = analyte.method if spec.lab == "beta" else None

        if analyte.kind == "TEXT":
            raw_text, low, high, applies, warnings, _, _ = _range_text(analyte.range, spec.lab, sex, 0, None)
            spec.results.append(
                ExpectedResult(
                    sequence,
                    name,
                    "TEXT",
                    "Não reagente",
                    None,
                    None,
                    "Não reagente",
                    None,
                    raw_text,
                    low,
                    high,
                    applies,
                    None,
                    method,
                    warnings=warnings,
                    analyte_code=code,
                )
            )
            continue

        unit, decimals, scale, rng, thousands = _lab_variant(analyte, spec.lab)
        value = Decimal(str(_true_value(spec.patient, analyte, when, origin) * scale))
        if thousands:  # counts are reported rounded
            step = Decimal(1000) if value >= 100000 else Decimal(10)
            value = (value / step).quantize(Decimal(1), rounding=ROUND_HALF_UP) * step
        raw_text, low, high, applies, warnings, p_low, p_high = _range_text(rng, spec.lab, sex, decimals, unit)

        comparator = None
        if analyte.censor_below is not None and value < Decimal(str(analyte.censor_below)):
            value = Decimal(str(analyte.censor_below))
            comparator = "<"
        printed = fmt_number(value, decimals, thousands)
        if comparator:
            printed = ("Inferior a " if spec.lab == "beta" else "< ") + printed

        number = value.quantize(Decimal(1).scaleb(-decimals), rounding=ROUND_HALF_UP)
        flag = None if comparator else _flag(rng, number, p_low, p_high)
        lab_flag, printed_flag = None, ""
        if flag and spec.lab == "alfa":
            lab_flag, printed_flag = flag, "H" if flag == "HIGH" else "L"
        elif flag and spec.lab == "beta":
            lab_flag, printed_flag = flag, "[ALTO]" if flag == "HIGH" else "[BAIXO]"
        elif flag and spec.lab == "delta":
            lab_flag, printed_flag = "ABNORMAL", "A"

        spec.results.append(
            ExpectedResult(
                sequence,
                name,
                "NUMERIC",
                printed,
                plain_decimal(value, decimals),
                comparator,
                None,
                unit,
                raw_text,
                low,
                high,
                applies,
                lab_flag,
                method,
                warnings=warnings,
                analyte_code=code,
                printed_flag=printed_flag,
            )
        )


def expected_document(spec: ReportSpec) -> dict:
    report_warnings = [] if spec.collected_at else [{"code": "COLLECTION_DATE_MISSING"}]
    return {
        "laboratoryName": LAB_NAMES[spec.lab],
        "collectedAt": spec.collected_at.isoformat() if spec.collected_at else None,
        "collectedAtRaw": spec.collected_at_raw,
        "reportedAt": spec.reported_at.isoformat(),
        "pageCount": spec.page_count,
        "warnings": report_warnings,
        "results": [r.to_schema() for r in spec.results],
    }
