import pytest

from app.extraction.dates import parse_date
from app.extraction.numbers import decimal_string, parse_measurement, parse_range


@pytest.mark.parametrize(
    ("printed", "expected"),
    [
        ("1,25", "1.25"),
        ("245.000", "245000"),
        ("6.540", "6540"),
        ("1.234,5", "1234.5"),
        ("206", "206"),
        ("0,90", "0.90"),
        ("1.5", "1.5"),
        ("abc", None),
        ("1,2,3", None),
    ],
)
def test_decimal_string(printed, expected) -> None:
    assert decimal_string(printed) == expected


def test_measurements() -> None:
    assert parse_measurement("< 0,5").comparator == "<"
    assert parse_measurement("Inferior a 0,5").value_numeric == "0.5"
    assert parse_measurement("Não reagente").kind == "TEXT"


@pytest.mark.parametrize(
    ("raw", "low", "high", "applies_to", "multiple"),
    [
        ("70 a 99", "70", "99", None, False),
        ("Desejável: < 190 mg/dL", None, "190", None, False),
        ("Desejável: acima de 40", "40", None, None, False),
        ("Homens: 13,5 a 17,5", "13.5", "17.5", "Homens", False),
        ("F: 12 a 15,5 / M: 13,5 a 17,5", None, None, None, True),
        ("150.000 a 450.000", "150000", "450000", None, False),
    ],
)
def test_ranges(raw, low, high, applies_to, multiple) -> None:
    parsed = parse_range(raw)
    assert (parsed.low, parsed.high, parsed.applies_to, parsed.multiple) == (low, high, applies_to, multiple)


def test_dates() -> None:
    assert parse_date("15/09/2021 07:42").isoformat() == "2021-09-15"
    assert parse_date("16 mar 2022").isoformat() == "2022-03-16"
    assert parse_date("15/03/23").isoformat() == "2023-03-15"
    assert parse_date("2024-09-11").isoformat() == "2024-09-11"
    assert parse_date("31/02/2024") is None
