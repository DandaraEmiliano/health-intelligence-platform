import { IconCheck, IconPencil } from "@tabler/icons-react";
import { useState } from "react";

import { problemMessage } from "../api/messages";
import { useConsents, usePatient, useRecordConsents, useUpdatePatient } from "../api/queries";
import { navItem } from "../navigation";
import { DeleteAccount } from "../profile/DeleteAccount";
import { ProfileForm } from "../profile/ProfileForm";
import { sexLabel } from "../profile/options";
import { toMergePatch, valuesOf } from "../profile/values";
import { formatDate, formatDateTime } from "../ui/format";
import { PageHeader } from "../ui/PageHeader";

export function Profile() {
  const patient = usePatient();
  const update = useUpdatePatient();
  const consents = useConsents();
  const record = useRecordConsents();
  const [editing, setEditing] = useState(false);
  const [saved, setSaved] = useState(false);
  const [confirmRevoke, setConfirmRevoke] = useState(false);

  const current = patient.data;
  const consent = (type: string) => consents.data?.find((item) => item.type === type);
  const ai = consent("AI_PROCESSING");

  return (
    <section className="stack">
      <PageHeader item={navItem("/perfil")} />

      <div className="card">
        <div className="card-header">
          <h2 style={{ margin: 0 }}>Seus dados</h2>
          {!editing && current && (
            <button className="small" onClick={() => { update.reset(); setSaved(false); setEditing(true); }}>
              <IconPencil size={16} aria-hidden="true" /> Editar
            </button>
          )}
        </div>
        {saved && !editing && <p className="success-box" role="status" style={{ marginBottom: "0.8rem" }}>Perfil atualizado.</p>}
        {patient.isPending && <p className="muted">Carregando…</p>}
        {patient.isError && <p className="error-box" role="alert">{problemMessage(patient.error)}</p>}
        {current && !editing && (
          <dl className="definition">
            <dt>Nome</dt><dd>{current.data.fullName}</dd>
            <dt>Nascimento</dt><dd>{formatDate(current.data.birthDate)}</dd>
            <dt>Sexo biológico</dt><dd>{sexLabel(current.data.biologicalSex)}</dd>
            <dt>Altura</dt><dd>{current.data.heightCm ? `${current.data.heightCm} cm` : "Não informada"}</dd>
          </dl>
        )}
        {current && editing && (
          <ProfileForm
            key={current.etag}
            initial={valuesOf(current.data)}
            submitLabel="Salvar"
            pending={update.isPending}
            error={update.error}
            onCancel={() => setEditing(false)}
            onSubmit={(values) => {
              const patch = toMergePatch(current.data, values);
              if (Object.keys(patch).length === 0) {
                setEditing(false);
                return;
              }
              update.mutate(
                { body: patch, etag: current.etag ?? "" },
                { onSuccess: () => { setEditing(false); setSaved(true); } },
              );
            }}
          />
        )}
      </div>

      <div className="card">
        <h2>Privacidade e consentimentos</h2>
        {record.isError && <p className="error-box" role="alert">{problemMessage(record.error)}</p>}
        {(["TERMS_OF_USE", "PRIVACY_POLICY"] as const).map((type) => {
          const item = consent(type);
          return (
            <div key={type} className="switch-row">
              <div>
                <p>{type === "TERMS_OF_USE" ? "Termos de uso" : "Política de privacidade"}</p>
                <p className="hint">{item ? `Versão ${item.documentVersion}, aceita em ${formatDateTime(item.recordedAt)}` : "Não aceita"}</p>
              </div>
              {item?.granted && <span className="pill tone-teal"><IconCheck size={14} aria-hidden="true" /> Aceito</span>}
            </div>
          );
        })}
        <div className="switch-row">
          <div>
            <p>Leitura de laudos por IA</p>
            <p className="hint">
              {ai?.granted
                ? "Ligada. Os resultados dos seus laudos são extraídos para você revisar."
                : "Desligada. Seus laudos ficam guardados, mas não são lidos pela IA."}
            </p>
          </div>
          {ai?.granted ? (
            confirmRevoke ? (
              <div className="actions" role="group" aria-label="Confirmar desligamento">
                <button
                  className="small danger"
                  aria-busy={record.isPending}
                  onClick={() => record.mutate([{ type: "AI_PROCESSING", granted: false }], { onSuccess: () => setConfirmRevoke(false) })}
                >
                  Desligar
                </button>
                <button className="small ghost" onClick={() => setConfirmRevoke(false)}>Manter</button>
              </div>
            ) : (
              <button className="small" onClick={() => setConfirmRevoke(true)}>Desligar</button>
            )
          ) : (
            <button className="small primary" aria-busy={record.isPending} onClick={() => record.mutate([{ type: "AI_PROCESSING", granted: true }])}>
              Ligar
            </button>
          )}
        </div>
      </div>

      <DeleteAccount />
    </section>
  );
}
