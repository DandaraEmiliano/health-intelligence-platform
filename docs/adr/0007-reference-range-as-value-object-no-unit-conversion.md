# ADR 0007: Reference range as a value object; no unit conversion

- **Status:** Proposed
- **Date:** 2026-11-12
- **Deciders:** Dandara Emiliano (owner), Claude (architect)

## Context

A reference range belongs to a specific result: it depends on the laboratory, the method, the specimen, and often the population (sex, age, pregnancy, fasting). Ranges are printed in many forms: "15 a 150 ng/mL", "Desejável: > 40 mg/dL", "Inferior a 1,0", "Não reagente", or several ranges for different groups. Units for the same analyte differ between laboratories (mg/dL and mmol/L for glucose, for example).

Converting between units requires analyte-specific factors (molar mass). Getting them wrong produces plausible-looking but incorrect values.

## Decision

1. **The reference range is a value object of `LaboratoryResult`**, stored in the result row, not a shared `ReferenceRange` entity. Fields: `ref_range_text` (always kept as printed), `ref_low` and `ref_high` (parsed only when unambiguous), `ref_applies_to` (population qualifier as printed).
2. **The original unit is always preserved** (`unit_raw`). A normalized form (`unit_normalized`, for example lowercase with whitespace removed) is used only to test whether two units are the same, never to convert between them.
3. **No unit conversion in the MVP.** Time series and comparisons group points by normalized unit. Points in a different unit appear as a separate series with a notice.
4. **Abnormality has two sources, kept apart:** `lab_flag` (printed by the laboratory) and `computed_flag` (derived from numeric low and high). The laboratory's flag takes precedence. A computed flag is always labelled as computed. Neither is presented as a diagnosis.
5. Censored values ("< 0,5") store the number and a `comparator`. They are plotted as bounds, not as exact points.

## Alternatives considered

| Option | Pros | Cons |
| --- | --- | --- |
| Shared `ReferenceRange` entity per analyte | Less duplication | Wrong by design: ranges are per lab, method and population |
| Convert to a canonical unit per analyte | Single continuous series | Clinical risk if a factor is wrong; needs curated, validated conversion data (UCUM plus molar masses) and clinical review |
| Store only parsed low/high | Simple queries | Loses information for qualitative, multi-group and textual ranges |

## Consequences

- Charts can show several series for one analyte when laboratories differ in units. This is honest but less smooth.
- Unit conversion can be added later as a derived, clearly labelled view backed by a curated table and clinical review. It never replaces stored originals.
- The analyte catalogue maps names (aliases) to analytes. It does not define universal ranges.
