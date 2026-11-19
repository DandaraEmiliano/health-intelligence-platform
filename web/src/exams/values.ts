import type { LaboratoryResult, Measurement } from "../api/resources";
import type { Tone } from "../navigation";

/** "1,25" -> "1.25", "245.000" -> "245000", "1.234,5" -> "1234.5" (same rules as the extractor). */
export function decimalString(printed: string): string | null {
  const text = printed.trim();
  if (!/^-?\d{1,3}(?:\.\d{3})+(?:,\d+)?$|^-?\d+(?:,\d+)?$|^-?\d+\.\d{1,2}$/.test(text)) return null;
  if (text.includes(",")) return text.replace(/\./g, "").replace(",", ".");
  if (/\.\d{3}(?:\.|$)/.test(text)) return text.replace(/\./g, "");
  return text;
}

const COMPARATORS: [RegExp, Measurement["comparator"]][] = [
  [/^(?:<=|≤)\s*/, "<="],
  [/^(?:>=|≥)\s*/, ">="],
  [/^(?:<|inferior a|menor que)\s*/i, "<"],
  [/^(?:>|superior a|maior que)\s*/i, ">"],
];

/** What the patient typed, as printed -> a Measurement for the review request. */
export function measurementFrom(rawValue: string): Measurement {
  let text = rawValue.trim();
  let comparator: Measurement["comparator"] | undefined;
  for (const [pattern, symbol] of COMPARATORS) {
    const match = pattern.exec(text);
    if (match) {
      comparator = symbol;
      text = text.slice(match[0].length);
      break;
    }
  }
  const numeric = decimalString(text);
  if (numeric === null) return { kind: "TEXT", rawValue: rawValue.trim(), valueText: rawValue.trim() };
  return { kind: "NUMERIC", rawValue: rawValue.trim(), valueNumeric: numeric, ...(comparator ? { comparator } : {}) };
}

/** 1234.5 -> "1.234,5" for display. */
export function formatDecimal(value: string | null | undefined): string {
  if (value === null || value === undefined) return "";
  const [integer, fraction] = value.split(".");
  const grouped = integer.replace(/\B(?=(\d{3})+(?!\d))/g, ".");
  return fraction ? `${grouped},${fraction}` : grouped;
}

const FLAG_VIEW: Record<string, { label: string; tone: Tone }> = {
  HIGH: { label: "Acima da referência", tone: "coral" },
  LOW: { label: "Abaixo da referência", tone: "coral" },
  ABNORMAL: { label: "Alterado", tone: "coral" },
  CRITICAL: { label: "Crítico", tone: "coral" },
  IN_RANGE: { label: "Dentro da referência", tone: "teal" },
};

/** The laboratory's flag wins; a computed one says so (ADR 0007). Never a diagnosis. */
export function flagView(result: LaboratoryResult): { label: string; tone: Tone } | null {
  const flag = result.flag;
  if (!flag?.value) return null;
  const view = FLAG_VIEW[flag.value];
  if (!view) return null;
  return flag.source === "COMPUTED" ? { ...view, label: `${view.label} (calculado)` } : view;
}

export const WARNING_TEXT: Record<string, string> = {
  MULTIPLE_RANGES: "O laudo traz faixas diferentes por grupo (por exemplo, por sexo). Confira qual vale para você.",
  RANGE_UNPARSEABLE: "A referência não é numérica; confira o texto do laudo.",
  RANGE_LOW_ABOVE_HIGH: "A faixa lida tem o mínimo acima do máximo; confira.",
  UNIT_MISSING: "Não encontramos a unidade deste valor.",
  UNIT_UNKNOWN: "Unidade pouco comum; confira.",
  VALUE_NOT_NUMERIC: "O valor não é numérico.",
  DUPLICATE_TEST_NAME: "Este exame aparece mais de uma vez no laudo.",
  LOW_CONFIDENCE: "Não achamos este valor no trecho indicado do laudo. Confira com atenção.",
  COLLECTION_DATE_MISSING: "Não encontramos a data da coleta no laudo.",
  COLLECTION_DATE_IN_FUTURE: "A data da coleta lida está no futuro; confira.",
};

export const REVIEW_VIEW: Record<LaboratoryResult["reviewStatus"], { label: string; tone: Tone }> = {
  PENDING_REVIEW: { label: "Para revisar", tone: "amber" },
  VERIFIED: { label: "Confirmado", tone: "teal" },
  CORRECTED: { label: "Corrigido", tone: "lilac" },
  REJECTED: { label: "Rejeitado", tone: "gray" },
};
