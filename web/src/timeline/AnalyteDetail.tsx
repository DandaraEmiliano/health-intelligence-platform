import { IconArrowLeft } from "@tabler/icons-react";
import { Link, useParams } from "react-router";

import { problemMessage } from "../api/messages";
import { useAnalyteSeries } from "../api/queries";
import { formatDecimal } from "../exams/values";
import { formatDate } from "../ui/format";
import { SeriesChart } from "./Chart";

const FLAG: Record<string, string> = { HIGH: "Acima", LOW: "Abaixo", ABNORMAL: "Alterado", CRITICAL: "Crítico", IN_RANGE: "Dentro" };

export function AnalyteDetail() {
  const { analyteId = "" } = useParams();
  const series = useAnalyteSeries(analyteId);

  if (series.isPending) return <p className="loading">Carregando…</p>;
  if (series.isError) return <p className="error-box" role="alert">{problemMessage(series.error)}</p>;
  const { analyte, series: bySeries } = series.data;

  return (
    <section className="stack tone-teal">
      <Link to="/linha-do-tempo" className="back"><IconArrowLeft size={16} aria-hidden="true" /> Linha do tempo</Link>
      <header>
        <h1>{analyte.displayName}</h1>
        <p className="muted">{analyte.category}. Só resultados que você confirmou ou corrigiu. Isto não é um diagnóstico.</p>
      </header>
      {bySeries.length === 0 && <p className="muted">Ainda não há resultados verificados deste exame.</p>}
      {bySeries.length > 1 && (
        <p className="warning-line">Os laboratórios usaram unidades diferentes. Mostramos cada unidade em separado, sem converter valores.</p>
      )}
      {bySeries.map((s) => (
        <div key={s.unit ?? "sem unidade"} className="card stack">
          <h2 style={{ margin: 0 }}>Em {s.unit ?? "sem unidade"}</h2>
          <SeriesChart points={s.points} unit={s.unit} />
          <table className="points">
            <thead><tr><th>Coleta</th><th>Valor</th><th>Referência</th><th>Marcação</th><th>Laboratório</th></tr></thead>
            <tbody>
              {[...s.points].reverse().map((p) => (
                <tr key={p.resultId}>
                  <td><Link to={`/exames/${p.reportId}`}>{formatDate(p.collectedAt)}</Link></td>
                  <td>{p.comparator ? `${p.comparator} ` : ""}{formatDecimal(p.value)} {p.unit}</td>
                  <td>{p.referenceRange.rawText ?? "—"}</td>
                  <td>{p.flag ? `${FLAG[p.flag.value] ?? p.flag.value}${p.flag.source === "COMPUTED" ? " (calculado)" : ""}` : "—"}</td>
                  <td>{p.laboratoryName ?? "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ))}
    </section>
  );
}
