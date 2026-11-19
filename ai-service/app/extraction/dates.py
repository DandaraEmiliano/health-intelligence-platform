"""Dates as printed by Brazilian laboratories -> ISO 8601, or None when unsure."""

import re
from datetime import date

_MONTHS = {
    "jan": 1, "fev": 2, "mar": 3, "abr": 4, "mai": 5, "jun": 6,
    "jul": 7, "ago": 8, "set": 9, "out": 10, "nov": 11, "dez": 12,
}  # fmt: skip


def parse_date(printed: str | None) -> date | None:
    if not printed:
        return None
    text = printed.strip().lower()
    try:
        if m := re.match(r"^(\d{4})-(\d{2})-(\d{2})", text):
            return date(int(m[1]), int(m[2]), int(m[3]))
        if m := re.match(r"^(\d{1,2})/(\d{1,2})/(\d{4}|\d{2})\b", text):
            year = int(m[3]) if len(m[3]) == 4 else 2000 + int(m[3])
            return date(year, int(m[2]), int(m[1]))
        if m := re.match(r"^(\d{1,2})(?:\s+de)?\s+([a-zç]{3})[a-zç]*\.?(?:\s+de)?\s+(\d{4})\b", text):
            month = _MONTHS.get(m[2][:3])
            return date(int(m[3]), month, int(m[1])) if month else None
    except ValueError:
        return None
    return None
