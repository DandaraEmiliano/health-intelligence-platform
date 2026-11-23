import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactNode } from "react";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { acceptedConsents, assistantAnswer, fakeFetch, fakeState, patientRecord, type FakeApiState } from "../test/fakeApi";
import { AssistantPage } from "./AssistantPage";

let api: FakeApiState;

function renderAt(path = "/assistente") {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}>{children}</MemoryRouter></QueryClientProvider>
  );
  return render(
    <Routes>
      <Route path="/assistente" element={<AssistantPage />} />
      <Route path="/assistente/:conversationId" element={<AssistantPage />} />
      <Route path="/exames/:reportId" element={<p>Tela do exame</p>} />
    </Routes>,
    { wrapper },
  );
}

const asks = () => api.calls.filter((c) => c.method === "POST" && c.path.endsWith("/messages"));

describe("Assistente", () => {
  beforeEach(() => {
    api = fakeState({ consents: acceptedConsents(), patient: patientRecord() });
    vi.stubGlobal("fetch", fakeFetch(api));
  });

  it("asks for AI consent before anything else", async () => {
    api.consents = acceptedConsents(false);
    renderAt();

    expect(await screen.findByRole("heading", { name: "O assistente precisa da sua autorização" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Autorizar em Meu perfil" })).toHaveAttribute("href", "/perfil");
    expect(screen.queryByLabelText("Sua pergunta")).not.toBeInTheDocument();
    expect(screen.getByText("não faz diagnóstico")).toBeInTheDocument();
  });

  it("starts a conversation and shows the answer with the facts it used, linked to the exam", async () => {
    const user = userEvent.setup();
    renderAt();

    await user.type(await screen.findByLabelText("Sua pergunta"), "Como está minha glicose?");
    await user.click(screen.getByRole("button", { name: "Enviar" }));

    const answer = await screen.findByRole("article", { name: "Resposta do assistente" });
    expect(within(answer).getByText(/Sua glicose de 10\/11\/2026 foi 112 mg\/dL/)).toBeInTheDocument();
    const facts = within(answer).getByRole("region", { name: "Dados usados nesta resposta" });
    expect(within(facts).getByRole("link", { name: "Ver exame: Glicose" })).toHaveAttribute("href", "/exames/rep-1");
    expect(within(facts).getByText("Glicose: 112 mg/dL")).toBeInTheDocument();
    expect(within(answer).getByText("Glossário")).toBeInTheDocument();
    expect(within(answer).getByText("Preciso repetir esse exame?")).toBeInTheDocument();
    expect(within(answer).getByText(/não é diagnóstico/)).toBeInTheDocument();
    expect(screen.getByText("Como está minha glicose?", { selector: ".message p" })).toBeInTheDocument();

    expect(asks()).toHaveLength(1);
    expect(asks()[0].body).toEqual({ content: "Como está minha glicose?" });
    expect(asks()[0].headers["idempotency-key"]).toMatch(/^[0-9a-f-]{36}$/);
    expect(await screen.findByRole("link", { name: /Como está minha glicose\?/ })).toBeInTheDocument();
    expect(screen.getByLabelText("Sua pergunta")).toHaveValue("");
  });

  it("shows emergency guidance first and prominently", async () => {
    api.answers = [assistantAnswer({
      answer: "Dor no peito forte pode ser uma emergência.",
      facts: [],
      seekProfessionalCare: { recommended: true, urgency: "EMERGENCY" },
      safety: { refused: true, reasonCode: "EMERGENCY_SIGNS" },
    })];
    const user = userEvent.setup();
    renderAt();

    await user.type(await screen.findByLabelText("Sua pergunta"), "Estou com dor no peito forte");
    await user.keyboard("{Enter}");

    const answer = await screen.findByRole("article", { name: "Resposta do assistente" });
    expect(within(answer).getByRole("alert")).toHaveTextContent("ligue 192");
    expect(within(answer).queryByRole("region", { name: "Dados usados nesta resposta" })).not.toBeInTheDocument();
  });

  it("when the assistant is unavailable, keeps the question and retries it with the same key", async () => {
    api.answers = [{ status: 503, code: "AI_UNAVAILABLE" }];
    const user = userEvent.setup();
    renderAt();

    await user.type(await screen.findByLabelText("Sua pergunta"), "Oi");
    await user.click(screen.getByRole("button", { name: "Enviar" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("O assistente está indisponível agora");
    await user.click(screen.getByRole("button", { name: "Tentar de novo" }));

    expect(await screen.findByRole("article", { name: "Resposta do assistente" })).toBeInTheDocument();
    expect(asks()).toHaveLength(2);
    expect(asks()[1].headers["idempotency-key"]).toBe(asks()[0].headers["idempotency-key"]);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("explains the rate limit", async () => {
    api.answers = [{ status: 429, code: "RATE_LIMITED" }];
    const user = userEvent.setup();
    renderAt();

    await user.type(await screen.findByLabelText("Sua pergunta"), "Oi");
    await user.click(screen.getByRole("button", { name: "Enviar" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("muitas perguntas em pouco tempo");
  });

  it("opens an earlier conversation and deletes it after confirmation", async () => {
    api.conversations["conv-old"] = {
      data: { id: "conv-old", title: "Meu colesterol", createdAt: "2026-11-01T10:00:00Z", updatedAt: "2026-11-01T10:00:00Z" },
      messages: [
        { messageId: "m-1", role: "USER", content: "Como está meu colesterol?", createdAt: "2026-11-01T10:00:00Z" },
        { ...assistantAnswer({ answer: "Seu colesterol total foi 190 mg/dL." }), messageId: "m-2" },
      ],
    };
    const user = userEvent.setup();
    renderAt("/assistente/conv-old");

    expect(await screen.findByText("Seu colesterol total foi 190 mg/dL.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Apagar conversa" }));
    await user.click(within(screen.getByRole("group", { name: "Confirmar exclusão da conversa" })).getByRole("button", { name: "Apagar" }));

    expect(await screen.findByRole("heading", { name: "Pergunte sobre seus exames" })).toBeInTheDocument();
    expect(api.conversations["conv-old"]).toBeUndefined();
  });
});
