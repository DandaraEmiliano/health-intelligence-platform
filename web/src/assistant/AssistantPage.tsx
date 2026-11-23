import { IconAlertTriangle, IconPlus, IconSend, IconSparkles, IconTrash } from "@tabler/icons-react";
import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from "react";
import { Link, NavLink, useNavigate, useParams } from "react-router";

import { problemCode, problemStatus } from "../api/http";
import { problemMessage } from "../api/messages";
import {
  keys,
  useAsk,
  useConsents,
  useConversation,
  useConversations,
  useCreateConversation,
  useDeleteConversation,
} from "../api/queries";
import type { ChatMessage } from "../api/resources";
import { navItem } from "../navigation";
import { formatDateTime } from "../ui/format";
import { PageHeader } from "../ui/PageHeader";
import { AnswerView } from "./AnswerView";

const QUESTION_MAX = 2000;

const SUGGESTIONS = [
  "O que mudou entre meus dois últimos exames?",
  "Quais resultados ficaram fora da faixa de referência?",
  "O que é o colesterol HDL?",
];

/** A question on its way, kept with its key so "try again" replays it instead of asking twice. */
interface Pending {
  conversationId: string;
  content: string;
  key: string;
}

export function AssistantPage() {
  const { conversationId } = useParams();
  const consents = useConsents();
  const aiGranted = consents.data?.find((c) => c.type === "AI_PROCESSING")?.granted === true;

  return (
    <section className="stack tone-pink">
      <PageHeader item={navItem("/assistente")} />
      <p className="card tinted disclaimer" role="note">
        O assistente explica apenas os resultados que você conferiu em Exames e o que você informou em Minha saúde.
        Ele <strong>não faz diagnóstico</strong>, não indica remédios nem doses e não substitui seu médico.
        Em uma emergência, ligue 192.
      </p>
      {consents.isPending && <p className="loading">Carregando…</p>}
      {consents.isSuccess && !aiGranted && (
        <div className="card empty">
          <span className="bubble large" aria-hidden="true"><IconSparkles size={30} stroke={1.6} /></span>
          <h3>O assistente precisa da sua autorização</h3>
          <p className="muted">Para responder, ele envia seus resultados verificados para processamento por IA.</p>
          <Link className="button primary" to="/perfil">Autorizar em Meu perfil</Link>
        </div>
      )}
      {aiGranted && <Assistant conversationId={conversationId} />}
    </section>
  );
}

function Assistant({ conversationId }: { conversationId: string | undefined }) {
  const navigate = useNavigate();
  const client = useQueryClient();
  const conversations = useConversations();
  const conversation = useConversation(conversationId);
  const create = useCreateConversation();
  const ask = useAsk();
  const remove = useDeleteConversation();
  const [draft, setDraft] = useState("");
  const [pending, setPending] = useState<Pending | null>(null);
  /** Id of the conversation whose deletion is being confirmed; moving to another one cancels it. */
  const [confirmingDelete, setConfirmingDelete] = useState<string | null>(null);
  const endRef = useRef<HTMLDivElement>(null);

  const messages = (conversation.data?.messages ?? []) as ChatMessage[];
  const pendingHere = pending && pending.conversationId === conversationId ? pending : null;

  useEffect(() => {
    endRef.current?.scrollIntoView?.({ block: "end" });
  }, [messages.length, pendingHere]);

  function send(question: Pending) {
    setPending(question);
    ask.mutate(
      { conversationId: question.conversationId, content: question.content, idempotencyKey: question.key },
      {
        onSuccess: async () => {
          await client.invalidateQueries({ queryKey: keys.conversation(question.conversationId) });
          setPending(null);
        },
      },
    );
  }

  async function submit(event?: FormEvent) {
    event?.preventDefault();
    const content = draft.trim();
    if (!content || ask.isPending || create.isPending) return;
    let id = conversationId;
    if (!id) {
      try {
        id = (await create.mutateAsync()).id;
      } catch {
        return;
      }
      navigate(`/assistente/${id}`);
    }
    setDraft("");
    send({ conversationId: id, content, key: crypto.randomUUID() });
  }

  function onKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault();
      void submit();
    }
  }

  const askError = pendingHere && ask.isError ? ask.error : null;
  // Asking again cannot help until the cause is fixed (consent) or the limit resets.
  const retryable = askError ? problemCode(askError) !== "AI_CONSENT_REQUIRED" && problemStatus(askError) !== 404 : false;
  const items = conversations.data ?? [];
  const busy = ask.isPending || create.isPending;

  return (
    <div className="assistant">
      <aside className="conversations" aria-label="Suas conversas">
        <button className="primary" onClick={() => navigate("/assistente")}>
          <IconPlus size={16} aria-hidden="true" /> Nova conversa
        </button>
        {conversations.isError && <p className="error-box" role="alert">{problemMessage(conversations.error)}</p>}
        {items.length > 0 && (
          <ul>
            {items.map((item) => (
              <li key={item.id}>
                <NavLink to={`/assistente/${item.id}`}>
                  <span className="title">{item.title ?? "Nova conversa"}</span>
                  <span className="hint">{formatDateTime(item.updatedAt)}</span>
                </NavLink>
              </li>
            ))}
          </ul>
        )}
      </aside>

      <div className="chat card">
        {conversationId && conversation.data && (
          <div className="chat-header">
            <h2>{conversation.data.title ?? "Nova conversa"}</h2>
            {confirmingDelete === conversationId ? (
              <div className="actions" role="group" aria-label="Confirmar exclusão da conversa">
                <span className="hint">Apagar esta conversa?</span>
                <button
                  className="small danger"
                  aria-busy={remove.isPending}
                  onClick={() => remove.mutate(conversationId, { onSuccess: () => navigate("/assistente") })}
                >
                  Apagar
                </button>
                <button className="small ghost" onClick={() => setConfirmingDelete(null)}>Não</button>
              </div>
            ) : (
              <button className="icon ghost" aria-label="Apagar conversa" onClick={() => setConfirmingDelete(conversationId)}>
                <IconTrash size={18} />
              </button>
            )}
          </div>
        )}
        {remove.isError && <p className="error-box" role="alert">{problemMessage(remove.error)}</p>}

        <div className="messages" aria-live="polite">
          {conversationId && conversation.isPending && <p className="loading">Carregando…</p>}
          {conversation.isError && <p className="error-box" role="alert">{problemMessage(conversation.error)}</p>}
          {!conversationId && !pendingHere && (
            <div className="empty">
              <span className="bubble large" aria-hidden="true"><IconSparkles size={30} stroke={1.6} /></span>
              <h3>Pergunte sobre seus exames</h3>
              <p className="muted">Cada resposta mostra de quais resultados ela veio, com link para o exame.</p>
              <div className="chips">
                {SUGGESTIONS.map((suggestion) => (
                  <button key={suggestion} className="small ghost suggestion" onClick={() => setDraft(suggestion)}>
                    {suggestion}
                  </button>
                ))}
              </div>
            </div>
          )}
          {messages.map((message) =>
            message.role === "USER" ? (
              <div key={message.messageId} className="message user">
                <p>{message.content}</p>
              </div>
            ) : (
              <AnswerView key={message.messageId} message={message} />
            ),
          )}
          {pendingHere && (
            <>
              <div className="message user pending">
                <p>{pendingHere.content}</p>
              </div>
              {ask.isPending && <p className="message assistant thinking" role="status">Consultando seus dados verificados…</p>}
              {askError && (
                <div className="error-box" role="alert">
                  <IconAlertTriangle size={16} aria-hidden="true" /> {problemMessage(askError)}{" "}
                  {retryable && <button className="small" onClick={() => send(pendingHere)}>Tentar de novo</button>}
                  {problemCode(askError) === "AI_CONSENT_REQUIRED" && <Link to="/perfil">Ir para Meu perfil</Link>}
                </div>
              )}
            </>
          )}
          <div ref={endRef} />
        </div>

        {create.isError && <p className="error-box" role="alert">{problemMessage(create.error)}</p>}
        <form className="composer" onSubmit={(event) => void submit(event)}>
          <label className="sr-only" htmlFor="question">Sua pergunta</label>
          <textarea
            id="question"
            rows={2}
            maxLength={QUESTION_MAX}
            placeholder="Pergunte sobre seus resultados…"
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            onKeyDown={onKeyDown}
          />
          <button type="submit" className="primary" disabled={!draft.trim() || busy} aria-busy={busy}>
            <IconSend size={16} aria-hidden="true" /> Enviar
          </button>
        </form>
        <p className="hint">Respostas geradas por IA a partir dos seus dados verificados. Confira com seu médico.</p>
      </div>
    </div>
  );
}
