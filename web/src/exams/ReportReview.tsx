import { IconAlertTriangle, IconArrowLeft, IconCheck, IconExternalLink, IconPencil, IconX } from "@tabler/icons-react";
import { useState } from "react";
import { Link, useParams } from "react-router";

import { problemMessage } from "../api/messages";
import { useReport, useReview } from "../api/queries";
import { documentApi, type LaboratoryResult, type ReviewRequest } from "../api/resources";
import { formatDate, formatDateTime } from "../ui/format";
import { CorrectionForm } from "./CorrectionForm";
import { flagView, formatDecimal, REVIEW_VIEW, WARNING_TEXT } from "./values";

export function ReportReview() {
  const { reportId = "" } = useParams();
  const report = useReport(reportId);
  const review = useReview(reportId);
  const [correcting, setCorrecting] = useState<string | null>(null);
  const [openError, setOpenError] = useState<unknown>(null);

  if (report.isPending) return <p className="loading">Carregando…</p>;
  if (report.isError) return <p className="error-box" role="alert">{problemMessage(report.error)}</p>;

  const { data, etag = "" } = report.data;
  const editable = data.status === "PENDING_REVIEW";
  const send = (body: ReviewRequest, after?: () => void) =>
    review.mutate({ body, etag }, { onSuccess: () => after?.() });
  const decide = (result: LaboratoryResult, action: "CONFIRM" | "REJECT") =>
    send({ decisions: [{ resultId: result.id, action }] });

  const openPdf = async () => {
    setOpenError(null);
    const tab = window.open("about:blank", "_blank");
    try {
      const link = await documentApi.contentLink(data.documentId);
      if (tab) tab.location.href = link.url;
    } catch (error) {
      tab?.close();
      setOpenError(error);
    }
  };

  const pending = data.pendingCount ?? 0;

  return (
    <section className="stack tone-amber">
      <Link to="/exames" className="back"><IconArrowLeft size={16} aria-hidden="true" /> Exames</Link>
      <header className="page-header">
        <div style={{ flex: 1 }}>
          <h1>{data.laboratoryName ?? "Laboratório não identificado"}</h1>
          <p className="muted">
            {data.collectedAt ? `Coleta em ${formatDate(data.collectedAt)}` : "Data da coleta não encontrada"}
            {data.collectedAtRaw && data.collectedAt ? ` (no laudo: ${data.collectedAtRaw})` : ""}
          </p>
        </div>
        <button className="small" onClick={() => void openPdf()}><IconExternalLink size={16} aria-hidden="true" /> Ver o PDF</button>
      </header>

      {editable ? (
        <div className="card tinted tone-amber">
          <p><strong>Confira cada resultado com o seu laudo.</strong> Só o que você confirmar ou corrigir é usado na linha do tempo e pelo assistente. Os resultados não são um diagnóstico.</p>
        </div>
      ) : (
        <div className={`card tinted tone-${data.status === "VERIFIED" ? "teal" : "gray"}`}>
          <p>{data.status === "VERIFIED" ? `Revisão concluída em ${formatDateTime(data.verifiedAt)}.` : "Você rejeitou todos os resultados deste laudo."}</p>
        </div>
      )}

      {(data.reportWarnings ?? []).map((w) => (
        <p key={w.code} className="warning-line"><IconAlertTriangle size={16} aria-hidden="true" /> {WARNING_TEXT[w.code] ?? w.code}</p>
      ))}
      {review.isError && <p className="error-box" role="alert">{problemMessage(review.error)}</p>}
      {openError !== null && <p className="error-box" role="alert">{problemMessage(openError)}</p>}

      <ul className="review-list" aria-label="Resultados">
        {data.results.map((result) => {
          const status = REVIEW_VIEW[result.reviewStatus];
          const flag = flagView(result);
          return (
            <li key={result.id} className={`review-item tone-${status.tone}`}>
              <div className="review-main">
                <div style={{ flex: 1, minWidth: 0 }}>
                  <p className="title">{result.rawName}</p>
                  <p className="value">
                    {result.measurement.kind === "NUMERIC"
                      ? `${result.measurement.comparator ?? ""} ${formatDecimal(result.measurement.valueNumeric)}`.trim()
                      : result.measurement.valueText}{" "}
                    {result.unit && <span className="unit">{result.unit}</span>}
                  </p>
                  <p className="sub">Referência: {result.referenceRange.rawText ?? "não informada"}</p>
                </div>
                <div className="pills">
                  {flag && <span className={`pill tone-${flag.tone}`}>{flag.label}</span>}
                  <span className="pill status">{status.label}</span>
                </div>
              </div>
              <p className="excerpt">No laudo, página {result.source.page}: <q>{result.source.excerpt}</q></p>
              {result.originalExtraction && (
                <p className="hint">Lido originalmente como “{result.originalExtraction.rawValue}”.</p>
              )}
              {(result.warnings ?? []).map((w) => (
                <p key={w.code} className="warning-line"><IconAlertTriangle size={16} aria-hidden="true" /> {WARNING_TEXT[w.code] ?? w.code}</p>
              ))}
              {editable && correcting === result.id ? (
                <CorrectionForm
                  result={result}
                  pending={review.isPending}
                  onCancel={() => setCorrecting(null)}
                  onSubmit={(correction) =>
                    send({ decisions: [{ resultId: result.id, action: "CORRECT", correction }] }, () => setCorrecting(null))
                  }
                />
              ) : (
                editable && (
                  <div className="review-actions" role="group" aria-label={`Decisão sobre ${result.rawName}`}>
                    <button className="small" aria-pressed={result.reviewStatus === "VERIFIED"} aria-busy={review.isPending} onClick={() => decide(result, "CONFIRM")}>
                      <IconCheck size={16} aria-hidden="true" /> Está certo
                    </button>
                    <button className="small" onClick={() => setCorrecting(result.id)}>
                      <IconPencil size={16} aria-hidden="true" /> Corrigir
                    </button>
                    <button className="small ghost" aria-pressed={result.reviewStatus === "REJECTED"} onClick={() => decide(result, "REJECT")}>
                      <IconX size={16} aria-hidden="true" /> Não é deste laudo
                    </button>
                  </div>
                )
              )}
            </li>
          );
        })}
      </ul>

      {editable && (
        <div className="card review-footer">
          <p>{pending === 0 ? "Tudo revisado." : `${pending} ${pending === 1 ? "resultado ainda sem decisão" : "resultados ainda sem decisão"}.`}</p>
          <div className="form-actions">
            {pending > 0 && (
              <button aria-busy={review.isPending} onClick={() => send({ decisions: [], confirmAllRemaining: true })}>
                Confirmar os {pending} restantes
              </button>
            )}
            <button className="primary" aria-busy={review.isPending} onClick={() => send({ decisions: [], finalize: true })}>
              Concluir revisão
            </button>
          </div>
        </div>
      )}
    </section>
  );
}
