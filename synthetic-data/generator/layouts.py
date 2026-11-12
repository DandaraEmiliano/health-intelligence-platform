"""Four fictitious laboratory layouts. Each draws a report and records page + excerpt per result.

Every page carries a "SYNTHETIC DOCUMENT" footer so a fixture can never pass as a real report.
`invariant=1` makes ReportLab output byte-for-byte reproducible.
"""

from pathlib import Path

from reportlab.lib.pagesizes import A4
from reportlab.pdfbase.pdfmetrics import stringWidth
from reportlab.pdfgen.canvas import Canvas

from generator.model import LAB_NAMES, ReportSpec

WIDTH, HEIGHT = A4
LEFT, RIGHT = 40, WIDTH - 40
BOTTOM = 70
FOOTER = "DOCUMENTO SINTÉTICO GERADO PARA TESTES - SEM VALOR CLÍNICO - NÃO REPRESENTA UM PACIENTE REAL"

SEX_TEXT = {"F": "Feminino", "M": "Masculino"}


class Page:
    def __init__(self, canvas: Canvas, spec: ReportSpec, header) -> None:
        self.canvas, self.spec, self.header = canvas, spec, header
        self.number = 1
        self.y = header(canvas, spec, 1)

    def need(self, height: float) -> None:
        if self.y - height < BOTTOM:
            self.new_page()

    def new_page(self) -> None:
        _footer(self.canvas)
        self.canvas.showPage()
        self.number += 1
        self.y = self.header(self.canvas, self.spec, self.number)

    def finish(self) -> int:
        _footer(self.canvas)
        self.canvas.save()
        return self.number


def _footer(canvas: Canvas) -> None:
    canvas.setFont("Helvetica-Oblique", 6.5)
    canvas.drawString(LEFT, 40, FOOTER)


def _text(canvas: Canvas, x: float, y: float, text: str, font: str = "Helvetica", size: float = 9) -> None:
    canvas.setFont(font, size)
    canvas.drawString(x, y, text)


def _patient_lines(spec: ReportSpec) -> list[str]:
    p = spec.patient
    return [
        f"Paciente: {p.name}  (ID sintético {p.synthetic_id})",
        f"Sexo: {SEX_TEXT[p.sex]}    Nascimento: {p.birth_date.strftime('%d/%m/%Y')}",
    ]


# --- Alfa: monospace table, one line per result, patient-specific ranges, H/L flags -------------------------


def _alfa_header(canvas: Canvas, spec: ReportSpec, page: int) -> float:
    _text(canvas, LEFT, HEIGHT - 50, LAB_NAMES["alfa"], "Helvetica-Bold", 14)
    _text(
        canvas,
        LEFT,
        HEIGHT - 64,
        "Rua Fictícia, 100 - São Paulo/SP - Responsável técnico: CRF-SP 00000 (fictício)",
        size=7.5,
    )
    y = HEIGHT - 86
    for line in _patient_lines(spec):
        _text(canvas, LEFT, y, line)
        y -= 12
    if spec.collected_at_raw:
        _text(canvas, LEFT, y, f"Data da coleta: {spec.collected_at_raw}    Liberado em: {spec.reported_at_raw}")
    y -= 12
    _text(canvas, RIGHT - 60, HEIGHT - 50, f"Página {page}", size=8)
    y -= 10
    _text(
        canvas,
        LEFT,
        y,
        f"{'EXAME':<36} {'RESULTADO':>11} {'UNIDADE':<9} {'':<2} VALOR DE REFERÊNCIA",
        "Courier-Bold",
        8.5,
    )
    return y - 14


def render_alfa(spec: ReportSpec, path: Path) -> None:
    canvas = Canvas(str(path), pagesize=A4, invariant=1)
    page = Page(canvas, spec, _alfa_header)
    for r in spec.results:
        page.need(12)
        line = f"{r.raw_name:<36} {r.raw_value:>11} {(r.unit_raw or ''):<9} {r.printed_flag:<2} {r.range_raw or ''}"
        _text(canvas, LEFT, page.y, line.rstrip(), "Courier", 8.5)
        r.page, r.excerpt = page.number, line.rstrip()
        page.y -= 12
    spec.page_count = page.finish()


# --- Beta: one block per exam, both sex ranges printed, [ALTO]/[BAIXO] ------------------------------------


def _beta_header(canvas: Canvas, spec: ReportSpec, page: int) -> float:
    _text(canvas, LEFT, HEIGHT - 50, LAB_NAMES["beta"].upper(), "Helvetica-Bold", 13)
    y = HEIGHT - 72
    for line in _patient_lines(spec):
        _text(canvas, LEFT, y, line, size=8.5)
        y -= 11
    if spec.collected_at_raw:
        _text(canvas, LEFT, y, f"Coletado em: {spec.collected_at_raw}", size=8.5)
        y -= 11
    _text(canvas, LEFT, y, f"Emissão: {spec.reported_at_raw}    Folha {page}", size=8.5)
    canvas.line(LEFT, y - 6, RIGHT, y - 6)
    return y - 24


def render_beta(spec: ReportSpec, path: Path) -> None:
    canvas = Canvas(str(path), pagesize=A4, invariant=1)
    page = Page(canvas, spec, _beta_header)
    for r in spec.results:
        page.need(52)
        _text(canvas, LEFT, page.y, r.raw_name, "Helvetica-Bold", 9)
        result_line = f"Resultado: {r.raw_value}" + (f" {r.unit_raw}" if r.unit_raw else "")
        result_line += f"  {r.printed_flag}" if r.printed_flag else ""
        _text(canvas, LEFT + 12, page.y - 12, result_line)
        _text(canvas, LEFT + 12, page.y - 24, f"Valor de referência: {r.range_raw}", size=8)
        if r.method:
            _text(canvas, LEFT + 12, page.y - 35, f"Método: {r.method}", "Helvetica-Oblique", 7.5)
        r.page, r.excerpt = page.number, result_line
        page.y -= 52
    spec.page_count = page.finish()


# --- Gama: two columns, always two pages, no flags, ISO dates ----------------------------------------------


def _gama_header(canvas: Canvas, spec: ReportSpec, page: int) -> float:
    _text(canvas, LEFT, HEIGHT - 48, LAB_NAMES["gama"], "Helvetica-Bold", 12)
    _text(canvas, RIGHT - 70, HEIGHT - 48, f"Página {page} de 2", size=8)
    y = HEIGHT - 68
    for line in _patient_lines(spec):
        _text(canvas, LEFT, y, line, size=8)
        y -= 10
    _text(canvas, LEFT, y, f"Coleta: {spec.collected_at_raw}    Laudo: {spec.reported_at_raw}", size=8)
    return y - 22


def render_gama(spec: ReportSpec, path: Path) -> None:
    canvas = Canvas(str(path), pagesize=A4, invariant=1)
    page = Page(canvas, spec, _gama_header)
    half = (len(spec.results) + 1) // 2
    column_width = (RIGHT - LEFT) / 2 - 10
    for index, r in enumerate(spec.results):
        if index == half:
            page.new_page()
        position = index if index < half else index - half
        column, row = position % 2, position // 2
        x = LEFT + column * (column_width + 20)
        y = page.y - row * 26
        line = f"{r.raw_name}: {r.raw_value}" + (f" {r.unit_raw}" if r.unit_raw else "")
        reference = f"Ref.: {r.range_raw}" if r.range_raw else "Ref.: não se aplica"
        size = 7.5
        while max(stringWidth(line, "Helvetica", size), stringWidth(reference, "Helvetica", size)) > column_width:
            size -= 0.25
        _text(canvas, x, y, line, size=size)
        _text(canvas, x + 8, y - 9, reference, size=size)
        r.page, r.excerpt = page.number, line
    spec.page_count = page.finish()
    if spec.page_count != 2:
        raise AssertionError(f"gama layout must have 2 pages, got {spec.page_count}")


# --- Delta: proportional-font table drawn cell by cell, both ranges, 'A' flag, observation box ---------------

DELTA_COLUMNS = (LEFT, 245, 305, 360, 378)


def _delta_header(canvas: Canvas, spec: ReportSpec, page: int) -> float:
    _text(canvas, LEFT, HEIGHT - 50, LAB_NAMES["delta"], "Helvetica-Bold", 12)
    y = HEIGHT - 70
    for line in _patient_lines(spec):
        _text(canvas, LEFT, y, line, size=8.5)
        y -= 11
    _text(
        canvas,
        LEFT,
        y,
        f"Coleta: {spec.collected_at_raw}  |  Resultado liberado: {spec.reported_at_raw}  |  pg. {page}",
        size=8.5,
    )
    y -= 20
    for x, title in zip(DELTA_COLUMNS, ("Exame", "Resultado", "Unidade", "", "Referência"), strict=True):
        _text(canvas, x, y, title, "Helvetica-Bold", 8.5)
    return y - 14


def render_delta(spec: ReportSpec, path: Path) -> None:
    canvas = Canvas(str(path), pagesize=A4, invariant=1)
    page = Page(canvas, spec, _delta_header)
    for r in spec.results:
        page.need(13)
        cells = (r.raw_name, r.raw_value, r.unit_raw or "", r.printed_flag, r.range_raw or "")
        for x, cell in zip(DELTA_COLUMNS, cells, strict=True):
            if cell:
                _text(canvas, x, page.y, cell, size=8)
        r.page, r.excerpt = page.number, " ".join(cell for cell in cells if cell)
        page.y -= 13
    if spec.observation:
        page.need(50)
        page.y -= 10
        _text(canvas, LEFT, page.y, "Observações:", "Helvetica-Bold", 8.5)
        _text(canvas, LEFT, page.y - 12, spec.observation, size=8)
    spec.page_count = page.finish()


RENDERERS = {"alfa": render_alfa, "beta": render_beta, "gama": render_gama, "delta": render_delta}
