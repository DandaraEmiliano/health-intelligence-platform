# Synthetic laboratory reports

Deterministic, **fully synthetic** laboratory report PDFs with ground truth. They are used to develop and evaluate the extraction pipeline (Phase 5) and as realistic test data for the core (Phase 4). No real patient data is used anywhere in this project.

Every page carries the footer *"DOCUMENTO SINTÉTICO GERADO PARA TESTES - SEM VALOR CLÍNICO"*. Reference ranges are plausible but synthetic and must not be read as clinical guidance.

## Contents

`fixtures/lab-reports/` holds 32 fixtures: 30 PDFs with a text layer (3 synthetic patients × 10 semiannual visits, 2021–2026) and 2 scanned PDFs without a text layer.

| File | Content |
| --- | --- |
| `<id>.pdf` | The report |
| `<id>.expected.json` | Ground truth: expected extraction status and, for text PDFs, the `report` payload of `document-extraction-result.v1` |
| `manifest.json` | Fixture list, coverage tags and SHA-256 of each PDF |

## Layouts (fictitious laboratories)

| Layout | Characteristics |
| --- | --- |
| `alfa` | Monospace table, one row per test; only the patient's sex-specific range; `H`/`L` flags; `dd/mm/yyyy hh:mm` dates |
| `beta` | One block per test with result, reference and method lines; both sex ranges printed; `[ALTO]`/`[BAIXO]`; platelets in `mil/mm³`; `dd mmm yyyy` dates |
| `gama` | Two columns, always two pages, no flags, ISO dates, "até"/"acima de" wording |
| `delta` | Proportional-font table drawn cell by cell; both sex ranges; `A` flag; glucose in `mmol/L`; `dd/mm/yy` dates; observation box (two fixtures carry a prompt-injection attempt) |

## Hard cases covered

Censored values (`< 0,5`, `Inferior a 0,5`), qualitative results (`Não reagente`), multiple ranges by sex, sex-specific single ranges, laboratory flags, non-default units, thousands separators (`245.000`), multi-page reports, a report with no collection date, prompt-injection text inside the document, and scanned PDFs (expected status `UNSUPPORTED_SCANNED`). The verifier fails if any of these disappears.

## Ground truth conventions

- `rawValue`, `rawName`, `unit.raw` and `referenceRange.rawText` are exactly as printed.
- `valueNumeric`, `low` and `high` use a dot decimal and no thousands separator (`"6.540"` printed is `"6540"`).
- When both sex ranges are printed, `low`/`high` are `null` and the result carries a `MULTIPLE_RANGES` warning (ADR 0007: parse only when unambiguous).
- `labFlag` is what the laboratory printed; `null` when the layout prints no flags, even if the value is out of range.
- `source.excerpt` is the printed line that contains the value, as found in the page text.
- `analyteCodes` maps each result sequence to the synthetic catalogue code, for normalization tests.

## Usage

```bash
cd synthetic-data
uv sync
uv run python -m generator.generate      # regenerate fixtures (byte-for-byte deterministic)
uv run python -m generator.verify        # schema, page text, hashes and coverage checks
```

CI regenerates the fixtures into a temporary directory, compares them with the committed files and runs the verifier.
