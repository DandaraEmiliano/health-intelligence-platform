import { IconAlertTriangle, IconArrowRight, IconFileUpload, IconPill, IconSparkles, IconStethoscope } from "@tabler/icons-react";
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router";

import { fetchReadiness } from "../api/health";
import { useConsents, useHealthRecords, usePatient } from "../api/queries";
import { firstName, greeting } from "../ui/format";

export function Home() {
  const patient = usePatient();
  const consents = useConsents();
  const allergies = useHealthRecords("allergies");
  const medications = useHealthRecords("medications");
  const conditions = useHealthRecords("conditions");
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

      <div className="card tone-blue" style={{ display: "flex", gap: "0.9rem", alignItems: "center" }}>
        <span className="bubble" aria-hidden="true"><IconFileUpload size={20} /></span>
        <div style={{ flex: 1 }}>
          <h3>Seus laudos</h3>
          <p className="muted" style={{ fontSize: "0.9rem" }}>O envio de PDFs chega na próxima etapa do projeto.</p>
        </div>
        <span className="pill">Em breve</span>
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
