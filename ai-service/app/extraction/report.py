"""LLM transcription -> the `report` of document-extraction-result.v1, with validator warnings.

Validators (ADR 0006) run before review: they never change a value, they only attach warnings
the patient sees while reviewing.
"""

from datetime import date

from app.extraction.dates import parse_date
from app.extraction.model import ExtractedReport, ExtractedResult
from app.extraction.numbers import parse_measurement, parse_range
from app.extraction.pdf import PdfText, normalize_whitespace


def build_report(extracted: ExtractedReport, pdf: PdfText, today: date) -> dict:
    collected = parse_date(extracted.collected_at_raw)
    reported = parse_date(extracted.reported_at_raw)
    warnings = []
    if collected is None:
        warnings.append({"code": "COLLECTION_DATE_MISSING"})
    elif collected > today:
        warnings.append({"code": "COLLECTION_DATE_IN_FUTURE"})

    seen_names: dict[str, int] = {}
    results = []
    for sequence, item in enumerate(extracted.results, start=1):
        result = _result(sequence, item, pdf)
        key = normalize_whitespace(item.raw_name).casefold()
        if key in seen_names:
            result.setdefault("warnings", []).append({"code": "DUPLICATE_TEST_NAME"})
        seen_names[key] = sequence
        results.append(result)

    return {
        "laboratoryName": _clip(extracted.laboratory_name, 200),
        "collectedAt": collected.isoformat() if collected else None,
        "collectedAtRaw": _clip(extracted.collected_at_raw, 100),
        "reportedAt": reported.isoformat() if reported else None,
        "pageCount": pdf.page_count,
        "warnings": warnings,
        "results": results,
    }


def _result(sequence: int, item: ExtractedResult, pdf: PdfText) -> dict:
    measurement = parse_measurement(item.raw_value)
    measurement_json: dict = {"kind": measurement.kind, "rawValue": item.raw_value.strip()}
    if measurement.kind == "NUMERIC":
        measurement_json["valueNumeric"] = measurement.value_numeric
        if measurement.comparator:
            measurement_json["comparator"] = measurement.comparator
    else:
        measurement_json["valueText"] = measurement.value_text

    reference = parse_range(item.reference_range_raw)
    warnings = []
    if reference.multiple:
        warnings.append({"code": "MULTIPLE_RANGES"})
    elif reference.unparseable:
        warnings.append({"code": "RANGE_UNPARSEABLE"})
    elif reference.low is not None and reference.high is not None and float(reference.low) > float(reference.high):
        warnings.append({"code": "RANGE_LOW_ABOVE_HIGH"})
    if measurement.kind == "NUMERIC" and not item.unit_raw:
        warnings.append({"code": "UNIT_MISSING"})
    if not _grounded(item, pdf):
        # The model's excerpt or value is not on the page it named: show it, but flag it.
        warnings.append({"code": "LOW_CONFIDENCE", "message": "Trecho não encontrado na página indicada."})

    result = {
        "sequence": sequence,
        "rawName": item.raw_name.strip(),
        "measurement": measurement_json,
        "unit": {"raw": item.unit_raw.strip() if item.unit_raw else None},
        "referenceRange": {
            "rawText": reference.raw_text,
            "low": reference.low,
            "high": reference.high,
            "appliesTo": reference.applies_to,
        },
        "labFlag": item.lab_flag,
        "method": item.method.strip() if item.method else None,
        "source": {"page": item.page, "excerpt": _clip(item.excerpt.strip(), 500)},
    }
    if warnings:
        result["warnings"] = warnings
    return result


def _grounded(item: ExtractedResult, pdf: PdfText) -> bool:
    if not 1 <= item.page <= pdf.page_count:
        return False
    page = normalize_whitespace(pdf.pages[item.page - 1])
    excerpt = normalize_whitespace(item.excerpt)
    return bool(excerpt) and excerpt in page and item.raw_value.strip() in excerpt


def _clip(value: str | None, limit: int) -> str | None:
    return value[:limit] if value else None
