import { IconPencil, IconPlus, IconTrash } from "@tabler/icons-react";
import { useState } from "react";
import { Link, useSearchParams } from "react-router";

import { problemMessage } from "../api/messages";
import { useDeleteHealthRecord, useHealthRecords, useSaveHealthRecord } from "../api/queries";
import { healthRecordApi, type HealthRecordKind, type RequestOf } from "../api/resources";
import { navItem } from "../navigation";
import { PageHeader } from "../ui/PageHeader";
import { KIND_ORDER, KINDS, toFormValues, toRequest, type FormValues } from "./kinds";
import { RecordForm } from "./RecordForm";

type Editing = { mode: "new" } | { mode: "edit"; id: string; etag: string; values: FormValues };

export function HealthRecordsPage() {
  const [params, setParams] = useSearchParams();
  const requested = params.get("aba") as HealthRecordKind | null;
  const kind: HealthRecordKind = requested && requested in KINDS ? requested : "allergies";
  return (
    <section className="tone-coral">
      <PageHeader item={navItem("/minha-saude")} />
      <nav className="tabs" aria-label="Tipos de registro">
        {KIND_ORDER.map((candidate) => {
          const config = KINDS[candidate];
          const Icon = config.icon;
          return (
            <Link
              key={candidate}
              to={`?aba=${candidate}`}
              aria-current={candidate === kind ? "page" : undefined}
              onClick={(event) => {
                event.preventDefault();
                setParams({ aba: candidate });
              }}
            >
              <Icon size={16} aria-hidden="true" />
              {config.label}
            </Link>
          );
        })}
      </nav>
      <KindPanel key={kind} kind={kind} />
    </section>
  );
}

function KindPanel({ kind }: { kind: HealthRecordKind }) {
  const config = KINDS[kind];
  const records = useHealthRecords(kind);
  const save = useSaveHealthRecord(kind);
  const remove = useDeleteHealthRecord(kind);
  const [editing, setEditing] = useState<Editing | null>(null);
  const [confirming, setConfirming] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [loadError, setLoadError] = useState<unknown>(null);

  /** Loads the record and its ETag. After a 412 it reloads but keeps the explanation on screen. */
  const startEdit = async (id: string, afterConflict = false) => {
    setNotice(null);
    setLoadError(null);
    try {
      const current = await healthRecordApi.get(kind, id);
      setEditing({ mode: "edit", id, etag: current.etag ?? "", values: toFormValues(config, current.data as Record<string, unknown>) });
      if (!afterConflict) save.reset();
    } catch (error) {
      setLoadError(error);
    }
  };

  const submit = (values: FormValues) => {
    const body = toRequest(config, values) as unknown as RequestOf<typeof kind>;
    const target = editing?.mode === "edit" ? { id: editing.id, etag: editing.etag } : {};
    save.mutate(
      { ...target, body },
      {
        onSuccess: () => {
          setEditing(null);
          setNotice(target.id ? "Alterações salvas." : "Registro adicionado.");
        },
        onError: (error) => {
          if ((error as { problem?: { status?: number } }).problem?.status === 412 && target.id) void startEdit(target.id, true);
        },
      },
    );
  };

  if (records.isPending) return <p className="loading">Carregando…</p>;
  if (records.isError) {
    return (
      <div className="error-box" role="alert">
        {problemMessage(records.error)} <button className="small" onClick={() => void records.refetch()}>Tentar de novo</button>
      </div>
    );
  }

  const items = records.data as unknown as Record<string, unknown>[];
  const Icon = config.icon;

  return (
    <div className="stack">
      {notice && <p className="success-box" role="status">{notice}</p>}
      {loadError !== null && <p className="error-box" role="alert">{problemMessage(loadError)}</p>}
      {remove.isError && <p className="error-box" role="alert">{problemMessage(remove.error)}</p>}

      {items.length === 0 && !editing && (
        <div className="card empty">
          <span className="bubble large" aria-hidden="true"><Icon size={30} stroke={1.6} /></span>
          <h3>{config.emptyTitle}</h3>
          <p className="muted">{config.emptyBody}</p>
          <button className="primary" onClick={() => { save.reset(); setNotice(null); setEditing({ mode: "new" }); }}>
            <IconPlus size={18} aria-hidden="true" /> {config.addLabel}
          </button>
        </div>
      )}

      {items.length > 0 && (
        <ul className="records" style={{ listStyle: "none", padding: 0, margin: 0 }}>
          {items.map((item) => {
            const id = String(item.id);
            const values = toFormValues(config, item);
            const pill = config.pill?.(values);
            return (
              <li key={id} className={`record tone-${config.tone(values)}`}>
                <div className="body">
                  <p className="title">{config.title(values)}</p>
                  {config.subtitle(values) && <p className="sub">{config.subtitle(values)}</p>}
                </div>
                {pill && <span className="pill">{pill}</span>}
                {confirming === id ? (
                  <div className="actions" role="group" aria-label="Confirmar exclusão">
                    <span className="sub">Excluir?</span>
                    <button
                      className="small danger"
                      aria-busy={remove.isPending}
                      onClick={() => remove.mutate(id, { onSuccess: () => { setConfirming(null); setNotice("Registro excluído."); } })}
                    >
                      Sim, excluir
                    </button>
                    <button className="small ghost" onClick={() => setConfirming(null)}>Não</button>
                  </div>
                ) : (
                  <div className="actions">
                    <button className="icon ghost" aria-label={`Editar ${config.title(values)}`} onClick={() => void startEdit(id)}>
                      <IconPencil size={18} />
                    </button>
                    <button className="icon ghost" aria-label={`Excluir ${config.title(values)}`} onClick={() => { setNotice(null); setConfirming(id); }}>
                      <IconTrash size={18} />
                    </button>
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      )}

      {editing ? (
        <RecordForm
          key={editing.mode === "edit" ? `${editing.id}-${editing.etag}` : "new"}
          config={config}
          title={editing.mode === "edit" ? `Editar ${config.title(editing.values)}` : config.addLabel}
          initial={editing.mode === "edit" ? editing.values : toFormValues(config, undefined)}
          pending={save.isPending}
          error={save.error}
          onSubmit={submit}
          onCancel={() => { setEditing(null); save.reset(); }}
        />
      ) : (
        items.length > 0 && (
          <div>
            <button className="primary" onClick={() => { save.reset(); setNotice(null); setEditing({ mode: "new" }); }}>
              <IconPlus size={18} aria-hidden="true" /> {config.addLabel}
            </button>
          </div>
        )
      )}
    </div>
  );
}
