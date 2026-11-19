"""What the LLM returns. Strings are copied exactly as printed; nothing is interpreted."""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class _Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


class ExtractedResult(_Strict):
    raw_name: str = Field(description="Test name exactly as printed.")
    raw_value: str = Field(description="Result exactly as printed, e.g. '206', '1,25', '< 0,5', 'Não reagente'.")
    unit_raw: str | None = Field(description="Unit exactly as printed, or null if none is printed.")
    reference_range_raw: str | None = Field(
        description="The whole reference range text for this test exactly as printed, or null if none is printed."
    )
    lab_flag: Literal["HIGH", "LOW", "ABNORMAL", "CRITICAL"] | None = Field(
        description="Only a marker the laboratory printed next to this result, mapped to the enum. "
        "Null if none is printed."
    )
    method: str | None = Field(description="Analytical method if printed for this test, else null.")
    page: int = Field(description="1-based page number where the result value is printed.")
    excerpt: str = Field(description="The printed line that contains the result value, copied exactly from the page.")


class ExtractedReport(_Strict):
    is_lab_report: bool = Field(description="True only if the document is a laboratory test report.")
    laboratory_name: str | None = Field(description="Laboratory name as printed, or null.")
    collected_at_raw: str | None = Field(description="Specimen collection date as printed, or null if not printed.")
    reported_at_raw: str | None = Field(description="Report release date as printed, or null if not printed.")
    results: list[ExtractedResult] = Field(description="Every test result in the order it appears.")
