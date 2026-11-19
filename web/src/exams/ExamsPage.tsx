import { IconChevronRight, IconFileUpload } from "@tabler/icons-react";
import { Link } from "react-router";

import { problemMessage } from "../api/messages";
import { useReports } from "../api/queries";
import { navItem } from "../navigation";
import { formatDate } from "../ui/format";
import { PageHeader } from "../ui/PageHeader";

const STATUS: Record<string, { label: string; tone: string }> = {
  PENDING_REVIEW: { label: "Para revisar", tone: "amber" },
  VERIFIED: { label: "Verificado", tone: "teal" },
  REJECTED: { label: "Rejeitado", tone: "gray" },
};

export function ExamsPage() {
  const reports = useReports();
  const items = reports.data?.pages.flatMap((page) => page.items) ?? [];

  return (
    <section className="stack tone-amber">
      <PageHeader item={navItem("/exames")} />
      {reports.isPending && <p className="loading">Carregando…</p>}
      {reports.isError && <p className="error-box" role="alert">{problemMessage(reports.error)}</p>}
      {reports.isSuccess && items.length === 0 && (
        <div className="card empty">
          <span className="bubble large" aria-hidden="true"><IconFileUpload size={30} stroke={1.6} /></span>
          <h3>Nenhum exame lido ainda</h3>
          <p className="muted">Quando um laudo enviado for lido, os resultados aparecem aqui para você conferir.</p>
          <Link className="button primary" to="/documentos">Enviar laudo</Link>
        </div>
      )}
      {items.length > 0 && (
        <ul className="records" aria-label="Seus laudos" style={{ listStyle: "none", padding: 0, margin: 0 }}>
          {items.map((report) => {
            const status = STATUS[report.status] ?? STATUS.PENDING_REVIEW;
            const pending = report.pendingCount ?? 0;
            return (
              <li key={report.id}>
                <Link to={`/exames/${report.id}`} className={`record tone-${status.tone} report-link`}>
                  <div className="body">
                    <p className="title">{report.laboratoryName ?? "Laboratório não identificado"}</p>
                    <p className="sub">
                      {report.collectedAt ? `Coleta em ${formatDate(report.collectedAt)}` : "Data da coleta não encontrada"} ·{" "}
                      {report.resultCount} {report.resultCount === 1 ? "resultado" : "resultados"}
                    </p>
                  </div>
                  <span className="pill">
                    {report.status === "PENDING_REVIEW" && pending > 0 ? `${pending} para revisar` : status.label}
                  </span>
                  <IconChevronRight size={18} aria-hidden="true" />
                </Link>
              </li>
            );
          })}
        </ul>
      )}
      {reports.hasNextPage && (
        <button style={{ justifySelf: "center" }} onClick={() => void reports.fetchNextPage()}>Carregar mais</button>
      )}
    </section>
  );
}
