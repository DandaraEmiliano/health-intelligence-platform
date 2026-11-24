"""Downloadable example reports for the public demo, and what ai-service should read from them.

Three fictitious visits of one fictitious patient, in a layout like a common Brazilian laboratory
report (one exam per line, dotted names, "Valor de referência", a patient block on every page).
Nothing here comes from a real person: names, values and the laboratory are invented.

ai-service recognises these exact files by SHA-256 and reads them without calling a model, so the
demo shows extraction, review and the timeline at no cost. Any other PDF still needs the model.

    cd synthetic-data && uv run python -m generator.examples

Writes web/public/exemplos/*.pdf and ai-service/app/extraction/examples.json. Pages are not
compressed, so the bytes are the same on every machine.
"""

import hashlib
import io
import json
from dataclasses import dataclass
from pathlib import Path

from reportlab.lib.pagesizes import A4
from reportlab.pdfgen.canvas import Canvas

ROOT = Path(__file__).resolve().parents[2]
PDF_DIR = ROOT / "web" / "public" / "exemplos"
ANSWERS = ROOT / "ai-service" / "app" / "extraction" / "examples.json"

WIDTH, HEIGHT = A4
LEFT = 40
LAB = "LABORATÓRIO EXEMPLO (FICTÍCIO)"
FOOTER = "DOCUMENTO SINTÉTICO PARA DEMONSTRAÇÃO - SEM VALOR CLÍNICO - NÃO REPRESENTA UM PACIENTE REAL"


@dataclass(frozen=True)
class Test:
    name: str  # as printed, before the dotted leader
    unit: str
    reference: str  # as printed after the unit
    section: str
    method: str


TESTS = [
    Test("Hemoglobina", "g/dL", "12,0 a 16,0", "HEMOGRAMA", "Automação hematológica"),
    Test("Hematócrito", "%", "36,0 a 46,0", "HEMOGRAMA", "Automação hematológica"),
    Test("V.C.M.", "fL", "81,0 a 100,0", "HEMOGRAMA", "Automação hematológica"),
    Test("Leucócitos", "/mm3", "4.500 a 10.000", "HEMOGRAMA", "Automação hematológica"),
    Test("Plaquetas", "/mm3", "150.000 a 450.000", "HEMOGRAMA", "Automação hematológica"),
    Test("Glicose", "mg/dL", "70 a 99", "GLICOSE EM JEJUM", "Enzimático (hexoquinase)"),
    Test("Colesterol total", "mg/dL", "< 190 mg/dL", "PERFIL LIPÍDICO", "Enzimático colorimétrico"),
    Test("Colesterol HDL", "mg/dL", "> 40 mg/dL", "PERFIL LIPÍDICO", "Enzimático colorimétrico"),
    Test("Colesterol LDL", "mg/dL", "< 115 mg/dL", "PERFIL LIPÍDICO", "Enzimático colorimétrico"),
    Test("Triglicérides", "mg/dL", "< 150 mg/dL", "PERFIL LIPÍDICO", "Enzimático colorimétrico"),
    Test("TGO (AST)", "U/L", "Até 35 U/L", "FUNÇÃO HEPÁTICA", "Cinético UV"),
    Test("TGP (ALT)", "U/L", "Até 35 U/L", "FUNÇÃO HEPÁTICA", "Cinético UV"),
    Test("Gama GT", "U/L", "Inferior a 40 U/L", "FUNÇÃO HEPÁTICA", "Enzimático colorimétrico"),
    Test("Creatinina", "mg/dL", "0,50 a 0,90", "FUNÇÃO RENAL", "Jaffé cinético"),
    Test("TSH", "mUI/L", "0,27 a 4,20", "TIREOIDE", "Eletroquimioluminescência"),
]

# Three visits six months apart: a few results start out of range and improve, so the timeline,
# the trends and the out-of-range list all have something to show.
VISITS = [
    ("2025-09-20", "20/09/2025 - 07:31", "21/09/2025 - 10:26", "001-10001-25",
     ["11,6", "35,2", "79,8", "6.100", "238.000", "104", "221", "44", "148", "172", "21", "38", "31", "0,72", "2,10"]),
    ("2026-03-21", "21/03/2026 - 07:40", "22/03/2026 - 09:58", "001-10002-26",
     ["12,3", "37,0", "82,4", "5.700", "251.000", "97", "204", "50", "129", "141", "19", "30", "27", "0,70", "2,35"]),
    ("2026-09-19", "19/09/2026 - 07:25", "20/09/2026 - 11:02", "001-10003-26",
     ["12,9", "38,6", "84,1", "5.400", "246.000", "91", "186", "57", "108", "112", "17", "24", "22", "0,74", "1,98"]),
]  # fmt: skip


def line_for(test: Test, value: str) -> str:
    return f"{test.name:.<18}: {value:>9} {test.unit:<7} {test.reference}"


def _patient_block(c: Canvas, order: str, requested: str) -> float:
    y = HEIGHT - 50
    c.setFont("Helvetica-Bold", 13)
    c.drawString(LEFT, y, LAB)
    c.setFont("Helvetica", 8)
    c.drawString(LEFT, y - 12, "Rua Fictícia, 100 - Cidade Exemplo - Responsável técnico: Dra. Exemplo (fictícia)")
    y -= 34
    c.setFont("Courier", 9)
    for text in (
        "Paciente   : PACIENTE EXEMPLO (FICTÍCIA)   (35 anos)",
        "Solicitante: DR. MÉDICO EXEMPLO (FICTÍCIO)",
        f"Código O.S.: {order}      Data: {requested}",
    ):
        c.drawString(LEFT, y, text)
        y -= 12
    c.line(LEFT, y, WIDTH - LEFT, y)
    return y - 22


def _footer(c: Canvas, page: int) -> None:
    c.setFont("Helvetica", 7)
    c.drawString(LEFT, 30, FOOTER)
    c.drawRightString(WIDTH - LEFT, 30, f"Página {page}")


def build(visit) -> tuple[bytes, dict]:
    iso, collected, released, order, values = visit
    buffer = io.BytesIO()
    c = Canvas(buffer, pagesize=A4, invariant=1, pageCompression=0)
    c.setTitle(f"Laudo de exemplo {iso} (fictício)")
    c.setAuthor(LAB)
    page = 1
    y = _patient_block(c, order, collected)
    results = []
    section = None
    for test, value in zip(TESTS, values, strict=True):
        if test.section != section:
            if section is not None and test.section in ("PERFIL LIPÍDICO",):
                _footer(c, page)
                c.showPage()
                page += 1
                y = _patient_block(c, order, collected)
            elif section is not None:
                y -= 8
            section = test.section
            c.setFont("Helvetica-Bold", 10)
            c.drawString(LEFT, y, section)
            c.setFont("Helvetica", 8)
            c.drawString(LEFT, y - 11, f"Material: Sangue / Soro    Método: {test.method}")
            c.drawString(LEFT + 330, y - 11, "Valor de referência:")
            y -= 26
        c.setFont("Courier", 9)
        text = line_for(test, value)
        c.drawString(LEFT, y, text)
        y -= 13
        results.append({
            "raw_name": test.name,
            "raw_value": value,
            "unit_raw": test.unit,
            "reference_range_raw": test.reference,
            "lab_flag": None,
            "method": test.method,
            "page": page,
            "excerpt": text,
        })  # fmt: skip
    y -= 16
    c.setFont("Courier", 9)
    c.drawString(LEFT, y, f"Data coleta: {collected}     Data liberação: {released}")
    c.drawString(LEFT, y - 12, "Responsável pela liberação: Dr. Exemplo Fictício - CRM 00.000")
    _footer(c, page)
    c.save()
    answer = {
        "is_lab_report": True,
        "laboratory_name": LAB,
        "collected_at_raw": collected,
        "reported_at_raw": released,
        "results": results,
    }
    return buffer.getvalue(), answer


def main() -> None:
    PDF_DIR.mkdir(parents=True, exist_ok=True)
    answers = {}
    for visit in VISITS:
        content, answer = build(visit)
        name = f"laudo-exemplo-{visit[0]}.pdf"
        (PDF_DIR / name).write_bytes(content)
        answers[hashlib.sha256(content).hexdigest()] = {"file": name, "report": answer}
        print(f"{name}: {len(content)} bytes")
    ANSWERS.write_text(json.dumps(answers, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"answers: {ANSWERS.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
