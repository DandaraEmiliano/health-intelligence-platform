import { IconChevronRight, IconCircleCheck } from "@tabler/icons-react";
import { Link } from "react-router";

import { problemMessage } from "../api/messages";
import { useOutOfRange } from "../api/queries";
import { navItem } from "../navigation";
import { formatDate } from "../ui/format";
import { PageHeader } from "../ui/PageHeader";
import { resultLabel, valueText } from "./resultText";
import { flagView } from "./values";

/** Verified results the laboratory (or the computed range) marked outside the reference. Not a diagnosis. */
export function OutOfRangePage() {
  const results = useOutOfRange();
  const items = results.data?.pages.flatMap((page) => page.items) ?? [];

  return (
    <section className="stack tone-amber">
      <PageHeader
        item={navItem("/exames")}
        title="Fora da referência"
        subtitle="Resultados conferidos por você que ficaram fora da faixa informada pelo laboratório."
      />
      <p className="card tinted" role="note">
        Estar fora da faixa de referência não significa, sozinho, um problema de saúde. Converse com seu médico sobre esses resultados.
      </p>
      {results.isPending && <p className="loading">Carregando…</p>}
      {results.isError && <p className="error-box" role="alert">{problemMessage(results.error)}</p>}
      {results.isSuccess && items.length === 0 && (
        <div className="card empty">
          <span className="bubble large" aria-hidden="true"><IconCircleCheck size={30} stroke={1.6} /></span>
          <h3>Nenhum resultado fora da referência</h3>
          <p className="muted">Entre os exames que você já conferiu, nenhum valor ficou fora da faixa.</p>
        </div>
      )}
      {items.length > 0 && (
        <ul className="records" aria-label="Resultados fora da referência" style={{ listStyle: "none", padding: 0, margin: 0 }}>
          {items.map((result) => {
            const flag = flagView(result);
            return (
              <li key={result.id}>
                <Link to={`/exames/${result.reportId}`} className="record tone-coral report-link">
                  <div className="body">
                    <p className="title">{resultLabel(result)}: {valueText(result)}</p>
                    <p className="sub">
                      {result.collectedAt ? `Coleta em ${formatDate(result.collectedAt)}` : "Data da coleta não informada"}
                      {result.referenceRange.rawText ? ` · Referência: ${result.referenceRange.rawText}` : ""}
                    </p>
                  </div>
                  {flag && <span className="pill">{flag.label}</span>}
                  <IconChevronRight size={18} aria-hidden="true" />
                </Link>
              </li>
            );
          })}
        </ul>
      )}
      {results.hasNextPage && (
        <button style={{ justifySelf: "center" }} onClick={() => void results.fetchNextPage()}>Carregar mais</button>
      )}
    </section>
  );
}
