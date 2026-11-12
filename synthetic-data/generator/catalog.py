"""Synthetic analyte catalogue and patients.

Reference ranges here are plausible but SYNTHETIC: they exist to exercise the extractor
(formats, units, sex-specific ranges), not as clinical guidance.
"""

from dataclasses import dataclass, field
from datetime import date
from typing import Literal

Sex = Literal["F", "M"]


@dataclass(frozen=True)
class Range:
    """Reference range specification.

    kind: between (low..high), lt (< high), gt (> low), text (qualitative), none (not printed).
    by_sex: sex-specific (low, high) pairs; overrides low/high.
    label: optional prefix printed before the range, e.g. "Desejável".
    """

    kind: Literal["between", "lt", "gt", "text", "none"]
    low: float | None = None
    high: float | None = None
    text: str | None = None
    by_sex: dict[str, tuple[float, float]] | None = None
    label: str | None = None

    def limits_for(self, sex: Sex) -> tuple[float | None, float | None]:
        if self.by_sex:
            return self.by_sex[sex]
        return self.low, self.high


@dataclass(frozen=True)
class Analyte:
    code: str
    name: str
    unit: str | None
    decimals: int
    range: Range
    kind: Literal["NUMERIC", "TEXT"] = "NUMERIC"
    baseline: float = 0.0
    spread: float = 0.0
    thousands: bool = False  # counts printed with thousands separator (e.g. 245.000)
    censor_below: float | None = None  # values below are printed as "< x"
    names: dict[str, str] = field(default_factory=dict)  # per-lab name overrides
    method: str | None = None


ANALYTES: dict[str, Analyte] = {
    a.code: a
    for a in [
        Analyte(
            "GLUCOSE",
            "Glicose",
            "mg/dL",
            0,
            Range("between", 70, 99),
            baseline=92,
            spread=14,
            names={"beta": "GLICOSE EM JEJUM", "delta": "Glicemia de jejum"},
            method="Enzimático (hexoquinase)",
        ),
        Analyte(
            "HBA1C",
            "Hemoglobina glicada (HbA1c)",
            "%",
            1,
            Range("between", 4.0, 5.6),
            baseline=5.5,
            spread=0.4,
            names={"beta": "HEMOGLOBINA GLICADA - HbA1c"},
            method="HPLC",
        ),
        Analyte(
            "CHOL_TOTAL",
            "Colesterol total",
            "mg/dL",
            0,
            Range("lt", high=190, label="Desejável"),
            baseline=198,
            spread=22,
            names={"beta": "COLESTEROL TOTAL"},
        ),
        Analyte(
            "HDL",
            "Colesterol HDL",
            "mg/dL",
            0,
            Range("gt", low=40, label="Desejável"),
            baseline=48,
            spread=8,
            names={"beta": "HDL-COLESTEROL", "delta": "HDL Colesterol"},
        ),
        Analyte(
            "LDL",
            "Colesterol LDL",
            "mg/dL",
            0,
            Range("lt", high=130, label="Desejável"),
            baseline=124,
            spread=20,
            names={"beta": "LDL-COLESTEROL"},
        ),
        Analyte(
            "TRIG",
            "Triglicerídeos",
            "mg/dL",
            0,
            Range("lt", high=150, label="Desejável"),
            baseline=138,
            spread=35,
            names={"beta": "TRIGLICERÍDEOS"},
        ),
        Analyte(
            "CREAT",
            "Creatinina",
            "mg/dL",
            2,
            Range("between", by_sex={"F": (0.50, 0.90), "M": (0.70, 1.20)}),
            baseline=0.86,
            spread=0.12,
            names={"beta": "CREATININA"},
        ),
        Analyte("UREA", "Ureia", "mg/dL", 0, Range("between", 17, 43), baseline=31, spread=7, names={"beta": "URÉIA"}),
        Analyte(
            "TSH",
            "TSH - Hormônio tireoestimulante",
            "µUI/mL",
            2,
            Range("between", 0.27, 4.20),
            baseline=2.4,
            spread=1.3,
            names={"beta": "TSH ULTRASSENSÍVEL", "delta": "TSH"},
        ),
        Analyte(
            "T4L",
            "T4 livre",
            "ng/dL",
            2,
            Range("between", 0.93, 1.70),
            baseline=1.25,
            spread=0.18,
            names={"beta": "T4 LIVRE"},
        ),
        Analyte(
            "FERRITIN",
            "Ferritina",
            "ng/mL",
            1,
            Range("between", by_sex={"F": (15, 150), "M": (30, 400)}),
            baseline=60,
            spread=40,
            names={"beta": "FERRITINA"},
        ),
        Analyte(
            "VITD",
            "25-hidroxivitamina D",
            "ng/mL",
            1,
            Range("between", 20, 60, label="Suficiência"),
            baseline=27,
            spread=8,
            names={"beta": "VITAMINA D (25-OH)"},
        ),
        Analyte(
            "B12",
            "Vitamina B12",
            "pg/mL",
            0,
            Range("between", 197, 771),
            baseline=420,
            spread=140,
            names={"beta": "VITAMINA B12"},
        ),
        Analyte(
            "HB",
            "Hemoglobina",
            "g/dL",
            1,
            Range("between", by_sex={"F": (12.0, 15.5), "M": (13.5, 17.5)}),
            baseline=13.6,
            spread=1.1,
            names={"beta": "HEMOGLOBINA"},
        ),
        Analyte(
            "WBC",
            "Leucócitos",
            "/mm³",
            0,
            Range("between", 4000, 11000),
            baseline=6800,
            spread=1600,
            thousands=True,
            names={"beta": "LEUCÓCITOS"},
        ),
        Analyte(
            "PLT",
            "Plaquetas",
            "/mm³",
            0,
            Range("between", 150000, 450000),
            baseline=245000,
            spread=60000,
            thousands=True,
            names={"beta": "PLAQUETAS"},
        ),
        Analyte(
            "AST",
            "TGO (AST)",
            "U/L",
            0,
            Range("lt", high=32),
            baseline=24,
            spread=8,
            names={"beta": "TRANSAMINASE OXALACÉTICA - TGO"},
        ),
        Analyte(
            "ALT",
            "TGP (ALT)",
            "U/L",
            0,
            Range("lt", high=33),
            baseline=26,
            spread=11,
            names={"beta": "TRANSAMINASE PIRÚVICA - TGP"},
        ),
        Analyte(
            "CRP_HS",
            "Proteína C reativa ultrassensível",
            "mg/L",
            1,
            Range("lt", high=1.0),
            baseline=0.7,
            spread=0.9,
            censor_below=0.5,
            names={"beta": "PCR ULTRASSENSÍVEL"},
        ),
        Analyte(
            "K",
            "Potássio",
            "mmol/L",
            1,
            Range("between", 3.5, 5.1),
            baseline=4.3,
            spread=0.4,
            names={"beta": "POTÁSSIO"},
        ),
        Analyte(
            "NA", "Sódio", "mmol/L", 0, Range("between", 136, 145), baseline=140, spread=2.5, names={"beta": "SÓDIO"}
        ),
        Analyte(
            "HBSAG",
            "HBsAg",
            None,
            0,
            Range("text", text="Não reagente"),
            kind="TEXT",
            names={"beta": "ANTÍGENO DE SUPERFÍCIE DO VÍRUS DA HEPATITE B - HBsAg"},
        ),
        Analyte(
            "HIV",
            "Anti-HIV 1 e 2",
            None,
            0,
            Range("text", text="Não reagente"),
            kind="TEXT",
            names={"beta": "ANTI-HIV 1/2 (TRIAGEM)"},
        ),
    ]
}

PANELS: dict[str, list[str]] = {
    "lipids": ["CHOL_TOTAL", "HDL", "LDL", "TRIG"],
    "metabolic": ["GLUCOSE", "HBA1C", "CREAT", "UREA", "K", "NA"],
    "thyroid": ["TSH", "T4L"],
    "blood": ["HB", "WBC", "PLT", "FERRITIN"],
    "liver": ["AST", "ALT"],
    "vitamins": ["VITD", "B12"],
    "inflammation": ["CRP_HS"],
    "serology": ["HBSAG", "HIV"],
}


@dataclass(frozen=True)
class Patient:
    synthetic_id: str
    name: str
    sex: Sex
    birth_date: date
    # Per-analyte multiplicative drift per year, to create visible trends.
    drift: dict[str, float] = field(default_factory=dict)


PATIENTS = [
    Patient(
        "SP-001",
        "Paciente Sintética Um",
        "F",
        date(1988, 4, 12),
        drift={"FERRITIN": -0.35, "HB": -0.05, "VITD": 0.10, "LDL": 0.04},
    ),
    Patient(
        "SP-002",
        "Paciente Sintético Dois",
        "M",
        date(1975, 9, 3),
        drift={"GLUCOSE": 0.08, "HBA1C": 0.05, "TRIG": 0.12, "ALT": 0.15, "CREAT": 0.06},
    ),
    Patient(
        "SP-003",
        "Paciente Sintética Três",
        "F",
        date(1961, 1, 27),
        drift={"TSH": 0.30, "CHOL_TOTAL": 0.05, "B12": -0.15, "CRP_HS": 0.4},
    ),
]
