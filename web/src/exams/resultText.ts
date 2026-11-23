import type { LaboratoryResult } from "../api/resources";
import { formatDecimal } from "./values";

/** The value as the patient reads it: "< 0,5 mg/dL", "Não reagente". */
export function valueText(result: LaboratoryResult): string {
  const m = result.measurement;
  const value = m.kind === "NUMERIC"
    ? `${m.comparator ? `${m.comparator} ` : ""}${formatDecimal(m.valueNumeric ?? null) || m.rawValue}`
    : (m.valueText ?? m.rawValue);
  return result.unit ? `${value} ${result.unit}` : value;
}

export function resultLabel(result: LaboratoryResult): string {
  return result.analyte?.displayName ?? result.rawName;
}
