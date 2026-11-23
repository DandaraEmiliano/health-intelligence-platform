import { useSearchParams } from "react-router";

import { problemMessage } from "../api/messages";
import { useComparison, useReports } from "../api/queries";
import type { LaboratoryReportSummary, ReportComparison } from "../api/resources";
import { navItem } from "../navigation";
import { formatDate } from "../ui/format";
import { PageHeader } from "../ui/PageHeader";
import { valueText } from "./resultText";
import { formatDecimal } from "./values";

type Row = ReportComparison["rows"][number];

const NO_DELTA: Record<string, string> = {
  UNIT_MISMATCH: "Unidades diferentes, sem comparação",
  NOT_NUMERIC: "Resultado em texto",
  MISSING_SIDE: "Só em um dos laudos",
  CENSORED_VALUE: "Valor com < ou >",
};

function reportName(report: LaboratoryReportSummary): string {
  const date = report.collectedAt ? formatDate(report.collectedAt) : "sem data";
  return `${date} · ${report.laboratoryName ?? "Laboratório não identificado"}`;
}

function deltaText(row: Row): string {
  if (row.delta === null || row.delta === undefined) return NO_DELTA[row.deltaUnavailableReason ?? ""] ?? "";
  if (/^-?0(\.0+)?$/.test(row.delta)) return "Sem mudança";
  const unit = row.right?.unit ? ` ${row.right.unit}` : "";
  return `${row.delta.startsWith("-") ? "" : "+"}${formatDecimal(row.delta)}${unit}`;
}

/**
 * Two finished reviews side by side. A difference is shown only for numbers in the same unit
 * (ADR 0007); it describes a change, never whether it is good or bad.
 */
export function ComparePage() {
  const [params, setParams] = useSearchParams();
  const reports = useReports();
  const verified = (reports.data?.pages.flatMap((page) => page.items) ?? []).filter((r) => r.status === "VERIFIED");
  const left = params.get("left");
  const right = params.get("right");
  const comparison = useComparison(left, right);

  function choose(side: "left" | "right", id: string) {
    const next = new URLSearchParams(params);
    if (id) next.set(side, id);
    else next.delete(side);
    setParams(next, { replace: true });
  }

  return (
    <section className="stack tone-amber">
      <PageHeader item={navItem("/exames")} title="Comparar laudos" subtitle="Veja lado a lado dois exames que você já conferiu." />
      {reports.isPending && <p className="loading">Carregando…</p>}
      {reports.isSuccess && verified.length < 2 && (
        <p className="card tinted">Para comparar, você precisa de pelo menos dois laudos com a revisão concluída.</p>
      )}
      {verified.length >= 2 && (
        <div className="form-row compare-pickers">
          {(["left", "right"] as const).map((side) => (
            <label key={side} className="field">
              <span>{side === "left" ? "Laudo anterior" : "Laudo mais recente"}</span>
              <select value={params.get(side) ?? ""} onChange={(event) => choose(side, event.target.value)}>
                <option value="">Escolha um laudo</option>
                {verified.map((report) => (
                  <option key={report.id} value={report.id}>{reportName(report)}</option>
                ))}
              </select>
            </label>
          ))}
        </div>
      )}
      {left && right && left === right && <p className="hint">Escolha dois laudos diferentes.</p>}
      {comparison.isPending && comparison.fetchStatus === "fetching" && <p className="loading">Comparando…</p>}
      {comparison.isError && <p className="error-box" role="alert">{problemMessage(comparison.error)}</p>}
      {comparison.data && (
        <div className="card table-wrap">
          <table className="compare">
            <caption className="sr-only">Comparação entre {reportName(comparison.data.left)} e {reportName(comparison.data.right)}</caption>
            <thead>
              <tr>
                <th scope="col">Exame</th>
                <th scope="col">{comparison.data.left.collectedAt ? formatDate(comparison.data.left.collectedAt) : "Anterior"}</th>
                <th scope="col">{comparison.data.right.collectedAt ? formatDate(comparison.data.right.collectedAt) : "Recente"}</th>
                <th scope="col">Diferença</th>
              </tr>
            </thead>
            <tbody>
              {comparison.data.rows.map((row, index) => (
                <tr key={row.analyte?.id ?? `${row.label}-${index}`}>
                  <th scope="row">{row.label}</th>
                  <td>{row.left ? valueText(row.left) : "—"}</td>
                  <td>{row.right ? valueText(row.right) : "—"}</td>
                  <td className={row.delta == null ? "hint" : ""}>{deltaText(row)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <p className="hint">A diferença mostra só a mudança no número. Converse com seu médico sobre o que ela significa.</p>
    </section>
  );
}
