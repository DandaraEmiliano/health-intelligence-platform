import { IconChevronRight, IconFileTypePdf, IconTestPipe } from "@tabler/icons-react";
import { Link } from "react-router";

import { problemMessage } from "../api/messages";
import { useTimeline, useTrackedAnalytes } from "../api/queries";
import type { TrackedAnalyte } from "../api/resources";
import { formatDecimal } from "../exams/values";
import { navItem } from "../navigation";
import { formatDate } from "../ui/format";
import { PageHeader } from "../ui/PageHeader";
import { Sparkline } from "./Chart";

function groupByCategory(items: TrackedAnalyte[]) {
  const groups = new Map<string, TrackedAnalyte[]>();
  for (const item of items) groups.set(item.analyte.category, [...(groups.get(item.analyte.category) ?? []), item]);
  return [...groups.entries()];
}

function latestText(item: TrackedAnalyte): string {
  const latest = item.latest;
  if (latest.value === undefined) return latest.valueText ?? "";
  return `${latest.comparator ? `${latest.comparator} ` : ""}${formatDecimal(latest.value)}`;
}

export function TimelinePage() {
  const tracked = useTrackedAnalytes();
  const timeline = useTimeline();
  const items = timeline.data?.pages.flatMap((page) => page.items) ?? [];

  return (
    <section className="stack tone-teal">
      <PageHeader item={navItem("/linha-do-tempo")} />

      {(tracked.isError || timeline.isError) && <p className="error-box" role="alert">{problemMessage(tracked.error ?? timeline.error)}</p>}
      {tracked.isPending && <p className="loading">Carregando…</p>}

      {tracked.isSuccess && tracked.data.length === 0 && (
        <div className="card empty">
          <span className="bubble large" aria-hidden="true"><IconTestPipe size={30} stroke={1.6} /></span>
          <h3>Sua evolução aparece aqui</h3>
          <p className="muted">Assim que você concluir a revisão de um laudo em Exames, os resultados confirmados entram na linha do tempo.</p>
          <Link className="button primary" to="/exames">Ir para Exames</Link>
        </div>
      )}

      {tracked.isSuccess && tracked.data.length > 0 && (
        <>
          <h2>Seus exames ao longo do tempo</h2>
          {groupByCategory(tracked.data).map(([category, group]) => (
            <div key={category} className="stack" style={{ gap: "0.5rem" }}>
              <h3 className="muted">{category}</h3>
              <ul className="analyte-grid" aria-label={category}>
                {group.map((item) => (
                  <li key={item.analyte.id}>
                    <Link to={`/linha-do-tempo/${item.analyte.id}`} className="analyte-card">
                      <div style={{ flex: 1, minWidth: 0 }}>
                        <p className="title">{item.analyte.displayName}</p>
                        <p className="value">{latestText(item)} <span className="unit">{item.latest.unit ?? ""}</span></p>
                        <p className="sub">{formatDate(item.latest.collectedAt)} · {item.count} {item.count === 1 ? "resultado" : "resultados"}</p>
                      </div>
                      <Sparkline values={item.recent.map((p) => Number(p.value))} />
                      {item.latest.flag && item.latest.flag.value !== "IN_RANGE" && <span className="pill tone-coral">Fora da referência</span>}
                    </Link>
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </>
      )}

      {items.length > 0 && (
        <>
          <h2>Histórico</h2>
          <ol className="history">
            {items.map((item) => (
              <li key={`${item.type}-${item.id}`}>
                <span className="when">{formatDate(item.occurredOn)}</span>
                {item.type === "LABORATORY_REPORT" ? (
                  <Link to={`/exames/${item.id}`} className="history-item">
                    <IconTestPipe size={18} aria-hidden="true" />
                    <span style={{ flex: 1 }}>
                      Coleta: {item.title} · {item.resultCount} {item.resultCount === 1 ? "resultado" : "resultados"}
                      {item.outOfRangeCount ? `, ${item.outOfRangeCount} fora da referência` : ""}
                    </span>
                    <IconChevronRight size={16} aria-hidden="true" />
                  </Link>
                ) : (
                  <Link to="/documentos" className="history-item">
                    <IconFileTypePdf size={18} aria-hidden="true" />
                    <span style={{ flex: 1 }}>Laudo enviado: {item.title}</span>
                    <IconChevronRight size={16} aria-hidden="true" />
                  </Link>
                )}
              </li>
            ))}
          </ol>
          {timeline.hasNextPage && <button style={{ justifySelf: "center" }} onClick={() => void timeline.fetchNextPage()}>Carregar mais</button>}
        </>
      )}
    </section>
  );
}
