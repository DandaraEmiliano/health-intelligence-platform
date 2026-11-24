import { IconCloudUpload, IconDownload, IconExternalLink, IconFileTypePdf, IconTrash } from "@tabler/icons-react";
import { useQueryClient } from "@tanstack/react-query";
import { useRef, useState, type DragEvent } from "react";
import { Link } from "react-router";

import { problemMessage } from "../api/messages";
import { keys, useConsents, useDeleteDocument, useDocuments } from "../api/queries";
import { documentApi, MAX_DOCUMENT_BYTES } from "../api/resources";
import { uploadDocument } from "../api/upload";
import { navItem } from "../navigation";
import { formatDateTime } from "../ui/format";
import { PageHeader } from "../ui/PageHeader";
import { formatSize, STATUS_VIEW } from "./statusView";

/** Fictitious reports the demo reads without a model (synthetic-data/generator/examples.py). */
const EXAMPLE_REPORTS = [
  { file: "laudo-exemplo-2025-09-20.pdf", label: "Setembro de 2025" },
  { file: "laudo-exemplo-2026-03-21.pdf", label: "Março de 2026" },
  { file: "laudo-exemplo-2026-09-19.pdf", label: "Setembro de 2026" },
];

interface UploadItem {
  key: string;
  name: string;
  progress: number;
  state: "sending" | "done" | "error";
  message?: string;
}

/** Client-side checks for quick feedback; the API checks the bytes again. */
function precheck(file: File): string | undefined {
  const looksPdf = file.type === "application/pdf" || file.name.toLowerCase().endsWith(".pdf");
  if (!looksPdf) return "Este arquivo não é um PDF.";
  if (file.size > MAX_DOCUMENT_BYTES) return "O arquivo passa de 10 MB.";
  if (file.size === 0) return "O arquivo está vazio.";
  return undefined;
}

export function DocumentsPage() {
  const documents = useDocuments();
  const consents = useConsents();
  const remove = useDeleteDocument();
  const client = useQueryClient();
  const input = useRef<HTMLInputElement>(null);
  const [dragging, setDragging] = useState(false);
  const [uploads, setUploads] = useState<UploadItem[]>([]);
  const [confirming, setConfirming] = useState<string | null>(null);
  const [openError, setOpenError] = useState<unknown>(null);

  const aiGranted = consents.data?.find((c) => c.type === "AI_PROCESSING")?.granted === true;

  const update = (key: string, change: Partial<UploadItem>) =>
    setUploads((items) => items.map((item) => (item.key === key ? { ...item, ...change } : item)));

  const send = (files: FileList | File[]) => {
    for (const file of Array.from(files)) {
      const key = crypto.randomUUID();
      const problem = precheck(file);
      setUploads((items) => [{ key, name: file.name, progress: 0, state: problem ? "error" : "sending", message: problem }, ...items]);
      if (problem) continue;
      uploadDocument(file, key, (progress) => update(key, { progress }))
        .then(() => {
          update(key, { state: "done", progress: 1 });
          void client.invalidateQueries({ queryKey: keys.documents });
        })
        .catch((error: unknown) => update(key, { state: "error", message: problemMessage(error) }));
    }
  };

  const onDrop = (event: DragEvent) => {
    event.preventDefault();
    setDragging(false);
    if (event.dataTransfer.files.length) send(event.dataTransfer.files);
  };

  const open = async (id: string) => {
    setOpenError(null);
    // Open the tab synchronously (pop-up blockers), then point it at the short-lived link.
    const tab = window.open("about:blank", "_blank");
    try {
      const link = await documentApi.contentLink(id);
      if (tab) tab.location.href = link.url;
      else window.location.href = link.url;
    } catch (error) {
      tab?.close();
      setOpenError(error);
    }
  };

  const items = documents.data?.pages.flatMap((page) => page.items) ?? [];

  return (
    <section className="stack tone-blue">
      <PageHeader item={navItem("/documentos")} />

      {consents.isSuccess && !aiGranted && (
        <p className="card tinted tone-pink" style={{ fontSize: "0.92rem" }}>
          A leitura por IA está desligada: seus PDFs ficam guardados, mas os resultados não são extraídos.{" "}
          <Link to="/perfil">Ligar em Meu perfil</Link>
        </p>
      )}

      <div
        className={`dropzone${dragging ? " over" : ""}`}
        onDragOver={(event) => { event.preventDefault(); setDragging(true); }}
        onDragLeave={() => setDragging(false)}
        onDrop={onDrop}
      >
        <span className="bubble solid large" aria-hidden="true"><IconCloudUpload size={30} stroke={1.7} /></span>
        <h3>Arraste seus laudos para cá</h3>
        <p className="muted">PDF original do laboratório, até 10 MB, sem senha.</p>
        <button className="primary" onClick={() => input.current?.click()}>Escolher arquivos</button>
        <input
          ref={input}
          type="file"
          accept="application/pdf,.pdf"
          multiple
          hidden
          aria-label="Escolher arquivos PDF"
          onChange={(event) => {
            if (event.target.files?.length) send(event.target.files);
            event.target.value = "";
          }}
        />
      </div>

      <div className="card examples">
        <h3>Quer testar sem usar seus exames?</h3>
        <p className="muted">
          Baixe os laudos de exemplo de uma paciente fictícia, de três datas diferentes, e envie aqui. Eles são lidos na
          hora, e depois de revisar os resultados você vê a evolução em Linha do tempo e Exames.
        </p>
        <ul className="example-links">
          {EXAMPLE_REPORTS.map((example) => (
            <li key={example.file}>
              <a href={`/exemplos/${example.file}`} download={example.file}>
                <IconDownload size={18} stroke={1.8} aria-hidden="true" /> Laudo de exemplo · {example.label}
              </a>
            </li>
          ))}
        </ul>
      </div>

      {uploads.length > 0 && (
        <ul className="uploads" aria-label="Envios">
          {uploads.map((item) => (
            <li key={item.key} className={`upload ${item.state}`}>
              <IconFileTypePdf size={20} aria-hidden="true" />
              <span className="name">{item.name}</span>
              {item.state === "sending" && (
                <span className="progress" role="progressbar" aria-label={`Enviando ${item.name}`} aria-valuenow={Math.round(item.progress * 100)} aria-valuemin={0} aria-valuemax={100}>
                  <span style={{ width: `${Math.round(item.progress * 100)}%` }} />
                </span>
              )}
              {item.state === "done" && <span className="pill tone-teal">Enviado</span>}
              {item.state === "error" && <span className="field-error" role="alert">{item.message}</span>}
            </li>
          ))}
        </ul>
      )}

      {openError !== null && <p className="error-box" role="alert">{problemMessage(openError)}</p>}
      {remove.isError && <p className="error-box" role="alert">{problemMessage(remove.error)}</p>}
      {documents.isError && <p className="error-box" role="alert">{problemMessage(documents.error)}</p>}
      {documents.isPending && <p className="loading">Carregando…</p>}

      {documents.isSuccess && items.length === 0 && uploads.length === 0 && (
        <p className="muted center" style={{ padding: "1rem" }}>Você ainda não enviou nenhum laudo.</p>
      )}

      {items.length > 0 && (
        <ul className="records" aria-label="Seus documentos" style={{ listStyle: "none", padding: 0, margin: 0 }}>
          {items.map((document) => {
            const view = STATUS_VIEW[document.status];
            return (
              <li key={document.id} className="record tone-blue doc">
                <span className="bubble" aria-hidden="true"><IconFileTypePdf size={20} /></span>
                <div className="body">
                  <p className="title">{document.originalFilename}</p>
                  <p className="sub">{formatSize(document.sizeBytes)} · enviado em {formatDateTime(document.uploadedAt)}</p>
                  <p className="sub">{view.hint}</p>
                </div>
                <span className={`pill tone-${view.tone}`}>{view.label}</span>
                {confirming === document.id ? (
                  <div className="actions" role="group" aria-label="Confirmar exclusão">
                    <span className="sub">Excluir?</span>
                    <button className="small danger" aria-busy={remove.isPending} onClick={() => remove.mutate(document.id, { onSuccess: () => setConfirming(null) })}>
                      Sim, excluir
                    </button>
                    <button className="small ghost" onClick={() => setConfirming(null)}>Não</button>
                  </div>
                ) : (
                  <div className="actions">
                    <button className="icon ghost" aria-label={`Abrir ${document.originalFilename}`} onClick={() => void open(document.id)}>
                      <IconExternalLink size={18} />
                    </button>
                    <button className="icon ghost" aria-label={`Excluir ${document.originalFilename}`} onClick={() => setConfirming(document.id)}>
                      <IconTrash size={18} />
                    </button>
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      )}

      {documents.hasNextPage && (
        <button style={{ justifySelf: "center" }} aria-busy={documents.isFetchingNextPage} onClick={() => void documents.fetchNextPage()}>
          Carregar mais
        </button>
      )}
    </section>
  );
}
