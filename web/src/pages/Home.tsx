import { IconAlertTriangle, IconArrowRight, IconClipboardCheck, IconFileUpload, IconPill, IconSparkles, IconStethoscope } from "@tabler/icons-react";
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router";

import { fetchReadiness } from "../api/health";
import { useConsents, useDocuments, useHealthRecords, useHealthSummary, usePatient, usePendingReviews } from "../api/queries";
import { resultLabel, valueText } from "../exams/resultText";
import { firstName, formatDate, greeting } from "../ui/format";

export function Home() {
  const patient = usePatient();
  const consents = useConsents();
  const allergies = useHealthRecords("allergies");
  const medications = useHealthRecords("medications");
  const conditions = useHealthRecords("conditions");
  const documents = useDocuments();
  const pendingReviews = usePendingReviews();
  const summary = useHealthSummary();
  const documentCount = documents.data?.pages.reduce((total, page) => total + page.items.length, 0);
  const readiness = useQuery({ queryKey: ["core-api-readiness"], queryFn: () => fetchReadiness() });

  const name = firstName(patient.data?.data.fullName);
  const aiGranted = consents.data?.find((consent) => consent.type === "AI_PROCESSING")?.granted === true;
  const activeMedications = medications.data?.filter((medication) => !medication.endedOn).length;
  const activeConditions = conditions.data?.filter((condition) => condition.status === "ACTIVE").length;

  return (
    <section className="stack">
      <header className="tone-lilac">
        <h1>{greeting()}{name ? `, ${name}` : ""}</h1>
        <p className="muted">Que bom te ver por aqui. Este é o resumo do que você já registrou.</p>
      </header>

      {(pendingReviews.data ?? 0) > 0 && (
        <div className="card tinted tone-amber" style={{ display: "flex", gap: "0.9rem", alignItems: "center" }}>
          <span className="bubble solid" aria-hidden="true"><IconClipboardCheck size={20} /></span>
          <div style={{ flex: 1 }}>
            <h3>
              {pendingReviews.data === 1 ? "1 laudo esperando sua revisão" : `${pendingReviews.data} laudos esperando sua revisão`}
            </h3>
            <p style={{ fontSize: "0.9rem" }}>Confira os valores antes de usarmos.</p>
          </div>
          <Link className="button small primary" to="/exames">Revisar agora</Link>
        </div>
      )}

      {consents.isSuccess && !aiGranted && (
        <div className="card tinted tone-pink" style={{ display: "flex", gap: "0.9rem", alignItems: "center" }}>
          <span className="bubble solid" aria-hidden="true"><IconSparkles size={20} /></span>
          <div style={{ flex: 1 }}>
            <h3>A leitura de laudos por IA está desligada</h3>
            <p style={{ fontSize: "0.9rem" }}>Sem ela, os laudos ficam guardados, mas os resultados não são extraídos.</p>
          </div>
          <Link className="button small" to="/perfil">Rever</Link>
        </div>
      )}

      <div className="grid-3">
        <Link className="stat tone-coral" to="/minha-saude?aba=allergies">
          <IconAlertTriangle size={20} aria-hidden="true" />
          <strong>{allergies.data?.length ?? "–"}</strong>
          <span>{allergies.data?.length === 1 ? "alergia" : "alergias"}</span>
        </Link>
        <Link className="stat tone-blue" to="/minha-saude?aba=medications">
          <IconPill size={20} aria-hidden="true" />
          <strong>{activeMedications ?? "–"}</strong>
          <span>{activeMedications === 1 ? "remédio em uso" : "remédios em uso"}</span>
        </Link>
        <Link className="stat tone-pink" to="/minha-saude?aba=conditions">
          <IconStethoscope size={20} aria-hidden="true" />
          <strong>{activeConditions ?? "–"}</strong>
          <span>{activeConditions === 1 ? "condição ativa" : "condições ativas"}</span>
        </Link>
      </div>

      {summary.data && summary.data.verifiedResultCount > 0 && (
        <section className="card tone-amber stack" aria-label="Seus exames conferidos" style={{ gap: "0.7rem" }}>
          <div style={{ display: "flex", gap: "0.9rem", alignItems: "center" }}>
            <span className="bubble" aria-hidden="true"><IconClipboardCheck size={20} /></span>
            <div style={{ flex: 1 }}>
              <h3>Seus exames conferidos</h3>
              <p className="muted" style={{ fontSize: "0.9rem" }}>
                {summary.data.verifiedResultCount} {summary.data.verifiedResultCount === 1 ? "resultado" : "resultados"}
                {summary.data.firstCollectedAt && summary.data.lastCollectedAt
                  ? summary.data.firstCollectedAt === summary.data.lastCollectedAt
                    ? ` de ${formatDate(summary.data.lastCollectedAt)}`
                    : ` entre ${formatDate(summary.data.firstCollectedAt)} e ${formatDate(summary.data.lastCollectedAt)}`
                  : ""}
                .
              </p>
            </div>
            <Link className="button small" to="/linha-do-tempo">Ver evolução</Link>
          </div>
          {(summary.data.recentOutOfRange ?? []).length > 0 && (
            <div>
              <p className="section-label">Fora da referência, mais recentes</p>
              <ul className="facts">
                {(summary.data.recentOutOfRange ?? []).slice(0, 3).map((result) => (
                  <li key={result.id}>
                    <Link className="fact" to={`/exames/${result.reportId}`}>
                      <span className="body">
                        <span className="title">{resultLabel(result)}: {valueText(result)}</span>
                        <span className="sub">{result.collectedAt ? `Coleta em ${formatDate(result.collectedAt)}` : ""}</span>
                      </span>
                    </Link>
                  </li>
                ))}
              </ul>
              <Link to="/exames/fora-da-referencia" style={{ fontSize: "0.88rem" }}>Ver todos fora da referência</Link>
            </div>
          )}
        </section>
      )}

      <div className="card tone-blue" style={{ display: "flex", gap: "0.9rem", alignItems: "center" }}>
        <span className="bubble" aria-hidden="true"><IconFileUpload size={20} /></span>
        <div style={{ flex: 1 }}>
          <h3>Seus laudos</h3>
          <p className="muted" style={{ fontSize: "0.9rem" }}>
            {documentCount === undefined
              ? "Carregando…"
              : documentCount === 0
                ? "Envie o PDF do seu último exame para começar."
                : `${documentCount}${documents.data?.pages.at(-1)?.nextCursor ? "+" : ""} ${documentCount === 1 ? "laudo enviado" : "laudos enviados"}.`}
          </p>
        </div>
        <Link className="button small primary" to="/documentos">Enviar laudo</Link>
      </div>

      <Link to="/minha-saude" className="button" style={{ justifySelf: "start" }}>
        Atualizar alergias, remédios e condições <IconArrowRight size={16} aria-hidden="true" />
      </Link>

      <p className="hint" aria-live="polite">
        <span className={`status-dot ${readiness.data === "UP" ? "up" : "down"}`} aria-hidden="true" />
        {readiness.isPending ? "Verificando o servidor…" : readiness.data === "UP" ? "Servidor disponível" : "Servidor indisponível"}
      </p>
    </section>
  );
}
