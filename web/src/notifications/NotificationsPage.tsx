import { IconBell, IconChevronRight } from "@tabler/icons-react";
import { Link } from "react-router";

import { problemMessage } from "../api/messages";
import { useMarkNotificationsRead, useNotifications } from "../api/queries";
import type { AppNotification } from "../api/resources";
import { formatDateTime } from "../ui/format";

const TEXT: Record<AppNotification["type"], { title: string; detail: string; tone: string }> = {
  REVIEW_PENDING: {
    title: "Laudo pronto para revisar",
    detail: "Confira os resultados lidos antes que eles entrem na sua linha do tempo.",
    tone: "amber",
  },
  DOCUMENT_PROCESSED: { title: "Documento lido", detail: "A leitura do seu documento terminou.", tone: "teal" },
  EXTRACTION_FAILED: {
    title: "Não conseguimos ler um documento",
    detail: "Veja o documento em Documentos e tente enviar de novo.",
    tone: "coral",
  },
  DOCUMENT_UNSUPPORTED: {
    title: "Documento não suportado",
    detail: "Parece uma imagem escaneada. Envie o PDF original do laboratório.",
    tone: "coral",
  },
};

function target(notification: AppNotification): string {
  if (notification.resource?.type === "LABORATORY_REPORT") return `/exames/${notification.resource.id}`;
  return "/documentos";
}

export function NotificationsPage() {
  const notifications = useNotifications();
  const markRead = useMarkNotificationsRead();
  const items = notifications.data ?? [];
  const unread = items.filter((n) => n.status === "UNREAD").map((n) => n.id);

  return (
    <section className="stack tone-lilac">
      <header className="page-header tone-lilac">
        <span className="bubble solid large" aria-hidden="true"><IconBell size={28} stroke={1.8} /></span>
        <div style={{ flex: 1 }}>
          <h1>Avisos</h1>
          <p>O que aconteceu com os documentos que você enviou.</p>
        </div>
        {unread.length > 0 && (
          <button className="small" aria-busy={markRead.isPending} onClick={() => markRead.mutate(unread)}>
            Marcar todos como lidos
          </button>
        )}
      </header>
      {notifications.isPending && <p className="loading">Carregando…</p>}
      {notifications.isError && <p className="error-box" role="alert">{problemMessage(notifications.error)}</p>}
      {markRead.isError && <p className="error-box" role="alert">{problemMessage(markRead.error)}</p>}
      {notifications.isSuccess && items.length === 0 && (
        <div className="card empty">
          <span className="bubble large" aria-hidden="true"><IconBell size={30} stroke={1.6} /></span>
          <h3>Nenhum aviso</h3>
          <p className="muted">Quando um documento enviado for lido, avisamos aqui.</p>
        </div>
      )}
      {items.length > 0 && (
        <ul className="records" aria-label="Seus avisos" style={{ listStyle: "none", padding: 0, margin: 0 }}>
          {items.map((notification) => {
            const text = TEXT[notification.type];
            const isUnread = notification.status === "UNREAD";
            return (
              <li key={notification.id}>
                <Link
                  to={target(notification)}
                  className={`record tone-${text.tone} report-link${isUnread ? " unread" : ""}`}
                  onClick={() => { if (isUnread) markRead.mutate([notification.id]); }}
                >
                  <div className="body">
                    <p className="title">{isUnread && <span className="sr-only">Não lido: </span>}{text.title}</p>
                    <p className="sub">{text.detail} · {formatDateTime(notification.createdAt)}</p>
                  </div>
                  {isUnread && <span className="pill">Novo</span>}
                  <IconChevronRight size={18} aria-hidden="true" />
                </Link>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
