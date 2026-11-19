import type { DocumentStatus } from "../api/resources";
import type { Tone } from "../navigation";

/** How each status reads to the patient. Honest about what exists: extraction arrives in Phase 5. */
export const STATUS_VIEW: Record<DocumentStatus, { label: string; tone: Tone; hint: string }> = {
  UPLOADED: { label: "Guardado", tone: "gray", hint: "A leitura por IA está desligada; o PDF fica guardado." },
  PROCESSING: { label: "Na fila para leitura", tone: "blue", hint: "Os resultados aparecem em Exames quando a leitura terminar." },
  PROCESSED: { label: "Lido", tone: "teal", hint: "Resultados lidos. Confira cada um em Exames antes que sejam usados." },
  FAILED: { label: "Não conseguimos ler", tone: "coral", hint: "Exclua e envie de novo, ou envie outro arquivo." },
  UNSUPPORTED_SCANNED: { label: "Foto ou digitalização", tone: "amber", hint: "Este PDF não tem texto; envie o laudo original do laboratório." },
};

export function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1).replace(".", ",")} MB`;
}
