import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactNode } from "react";
import { MemoryRouter } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { acceptedConsents, fakeFetch, fakeState, patientRecord, type FakeApiState } from "./test/fakeApi";

const signinRedirect = vi.fn(async () => undefined);
const authState = {
  isLoading: false,
  isAuthenticated: false,
  error: undefined as Error | undefined,
  user: undefined as { access_token: string; profile: { given_name?: string; email?: string } } | undefined,
  signinRedirect,
  signoutRedirect: vi.fn(async () => undefined),
};

vi.mock("react-oidc-context", () => ({ useAuth: () => authState }));

const { App } = await import("./App");
const { fetchReadiness } = await import("./api/health");

let api: FakeApiState;

function renderApp(path = "/") {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>{children}</MemoryRouter>
    </QueryClientProvider>
  );
  return render(<App />, { wrapper });
}

function signIn() {
  Object.assign(authState, { isAuthenticated: true, user: { access_token: "t", profile: { email: "ana@example.test" } } });
}

const writes = () => api.calls.filter((call) => call.method !== "GET");

describe("signed out", () => {
  beforeEach(() => {
    Object.assign(authState, { isLoading: false, isAuthenticated: false, error: undefined, user: undefined });
    api = fakeState();
    vi.stubGlobal("fetch", fakeFetch(api));
  });

  it("shows the landing page and starts sign-in", async () => {
    renderApp("/exames");

    expect(screen.getByRole("heading", { name: "Health Intelligence Platform" })).toBeInTheDocument();
    expect(screen.queryByRole("navigation", { name: "Principal" })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Entrar" }));
    expect(signinRedirect).toHaveBeenCalledOnce();
  });

  it("offers account creation through the identity provider", async () => {
    renderApp();
    await userEvent.click(screen.getByRole("button", { name: "Criar conta" }));
    expect(signinRedirect).toHaveBeenCalledWith({ prompt: "create" });
  });

  it("always shows the synthetic-data banner", () => {
    renderApp();
    expect(screen.getByRole("note")).toHaveTextContent("dados sintéticos");
  });
});

describe("onboarding", () => {
  beforeEach(() => {
    signIn();
    api = fakeState();
    vi.stubGlobal("fetch", fakeFetch(api));
  });

  it("takes a new user through terms, the AI question and the profile, then to the home page", async () => {
    renderApp();

    expect(await screen.findByRole("heading", { name: "Boas-vindas" })).toBeInTheDocument();
    expect(screen.queryByRole("navigation", { name: "Principal" })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Concordo e continuar" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Marque as duas opções");
    expect(writes()).toHaveLength(0);

    await userEvent.click(screen.getByRole("checkbox", { name: /Termos de uso/ }));
    await userEvent.click(screen.getByRole("checkbox", { name: /Política de privacidade/ }));
    await userEvent.click(screen.getByRole("button", { name: "Concordo e continuar" }));

    await userEvent.click(await screen.findByRole("button", { name: "Agora não" }));

    expect(await screen.findByRole("heading", { name: "Conte um pouco sobre você" })).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Criar meu perfil" }));
    expect(screen.getAllByText("Preencha este campo.")).toHaveLength(2);

    await userEvent.type(screen.getByLabelText("Nome completo"), "Ana Sintética");
    await userEvent.type(screen.getByLabelText("Data de nascimento"), "1990-04-12");
    await userEvent.click(screen.getByRole("radio", { name: "Feminino" }));
    await userEvent.click(screen.getByRole("button", { name: "Criar meu perfil" }));

    expect(await screen.findByRole("heading", { name: /, Ana$/ })).toBeInTheDocument();
    expect(screen.getByText("A leitura de laudos por IA está desligada")).toBeInTheDocument();
    expect(writes().map((call) => [call.method, call.path, call.body])).toEqual([
      ["POST", "/api/v1/consents", { type: "TERMS_OF_USE", granted: true, documentVersion: "2026.1" }],
      ["POST", "/api/v1/consents", { type: "PRIVACY_POLICY", granted: true, documentVersion: "2026.1" }],
      ["POST", "/api/v1/consents", { type: "AI_PROCESSING", granted: false, documentVersion: "ai-2026.1" }],
      ["POST", "/api/v1/patients", { fullName: "Ana Sintética", birthDate: "1990-04-12", biologicalSex: "FEMALE" }],
    ]);
  });

  it("asks only for what is missing", async () => {
    api.consents = acceptedConsents();
    renderApp();

    expect(await screen.findByRole("heading", { name: "Conte um pouco sobre você" })).toBeInTheDocument();
  });
});

describe("signed in", () => {
  beforeEach(() => {
    signIn();
    api = fakeState({ consents: acceptedConsents(), patient: patientRecord() });
    vi.stubGlobal("fetch", fakeFetch(api));
  });

  it("shows the shell, the greeting and real counts on the home page", async () => {
    api.records.allergies.push({ id: "a1", version: 0, data: { substance: "Penicilina", allergyType: "MEDICATION" } });

    renderApp();

    expect(await screen.findByRole("heading", { name: /, Ana$/ })).toBeInTheDocument();
    expect(screen.getByRole("navigation", { name: "Principal" })).toBeInTheDocument();
    expect(await screen.findByText("alergia")).toBeInTheDocument();
    expect(screen.queryByText("A leitura de laudos por IA está desligada")).not.toBeInTheDocument();
    expect(await screen.findByText("Servidor disponível")).toBeInTheDocument();
  });

  it("adds, lists and deletes an allergy", async () => {
    renderApp("/minha-saude");

    await userEvent.click(await screen.findByRole("button", { name: "Adicionar alergia" }));
    const form = screen.getByRole("form", { name: "Adicionar alergia" });
    await userEvent.click(within(form).getByRole("button", { name: "Salvar" }));
    expect(within(form).getByText("Escolha uma opção.")).toBeInTheDocument();
    expect(writes()).toHaveLength(0);

    await userEvent.type(within(form).getByLabelText("A que você tem alergia?"), "Dipirona");
    await userEvent.click(within(form).getByRole("radio", { name: "Remédio" }));
    await userEvent.click(within(form).getByRole("radio", { name: "Grave" }));
    await userEvent.click(within(form).getByRole("button", { name: "Salvar" }));

    expect(await screen.findByText("Registro adicionado.")).toBeInTheDocument();
    expect(await screen.findByText("Dipirona")).toBeInTheDocument();
    expect(screen.getByText("Grave")).toBeInTheDocument();
    expect(writes()[0].body).toEqual({ substance: "Dipirona", allergyType: "MEDICATION", severity: "SEVERE" });

    await userEvent.click(screen.getByRole("button", { name: "Excluir Dipirona" }));
    await userEvent.click(screen.getByRole("button", { name: "Sim, excluir" }));
    expect(await screen.findByText("Registre suas alergias")).toBeInTheDocument();
  });

  it("validates a medication period before sending it", async () => {
    renderApp("/minha-saude?aba=medications");

    await userEvent.click(await screen.findByRole("button", { name: "Adicionar remédio" }));
    await userEvent.type(screen.getByLabelText("Nome"), "Losartana");
    await userEvent.type(screen.getByLabelText(/Começou em/), "2025-05-01");
    await userEvent.type(screen.getByLabelText(/Parou em/), "2025-01-01");
    await userEvent.click(screen.getByRole("button", { name: "Salvar" }));

    expect(screen.getByText("O fim precisa ser depois do início.")).toBeInTheDocument();
    expect(writes()).toHaveLength(0);
  });

  it("edits with the ETag read when editing began and explains a conflict", async () => {
    api.records.conditions.push({ id: "c1", version: 0, data: { description: "Asma", status: "ACTIVE" } });
    renderApp("/minha-saude?aba=conditions");

    await userEvent.click(await screen.findByRole("button", { name: "Editar Asma" }));
    await userEvent.click(await screen.findByRole("radio", { name: "Resolvida" }));
    api.conflictOnNextWrite = true;
    await userEvent.click(screen.getByRole("button", { name: "Salvar" }));

    expect(await screen.findByText(/mudou enquanto você editava/)).toBeInTheDocument();
    const put = writes().find((call) => call.method === "PUT");
    expect(put?.headers["if-match"]).toBe('"0"');

    await userEvent.click(await screen.findByRole("radio", { name: "Resolvida" }));
    await userEvent.click(screen.getByRole("button", { name: "Salvar" }));
    expect(await screen.findByText("Alterações salvas.")).toBeInTheDocument();
    expect(writes().filter((call) => call.method === "PUT").at(-1)?.headers["if-match"]).toBe('"1"');
  });

  it("updates the profile with a merge patch of the changed fields only", async () => {
    api.patient = patientRecord({ heightCm: 165 });
    renderApp("/perfil");

    await userEvent.click(await screen.findByRole("button", { name: "Editar" }));
    const name = screen.getByLabelText("Nome completo");
    await userEvent.clear(name);
    await userEvent.type(name, "Ana Nova");
    await userEvent.clear(screen.getByLabelText(/Altura/));
    await userEvent.click(screen.getByRole("button", { name: "Salvar" }));

    expect(await screen.findByText("Perfil atualizado.")).toBeInTheDocument();
    const patch = writes().find((call) => call.method === "PATCH");
    expect(patch?.body).toEqual({ fullName: "Ana Nova", heightCm: null });
    expect(patch?.headers["if-match"]).toBe('"0"');
    expect(patch?.headers["content-type"]).toBe("application/merge-patch+json");
  });

  it("asks before turning AI reading off", async () => {
    renderApp("/perfil");

    await userEvent.click(await screen.findByRole("button", { name: "Desligar" }));
    const confirm = screen.getByRole("group", { name: "Confirmar desligamento" });
    await userEvent.click(within(confirm).getByRole("button", { name: "Desligar" }));

    await waitFor(() => expect(writes().at(-1)?.body).toEqual({ type: "AI_PROCESSING", granted: false, documentVersion: "ai-2026.1" }));
    expect(await screen.findByRole("button", { name: "Ligar" })).toBeInTheDocument();
  });

  it("deletes the account only after the typed confirmation, then signs out", async () => {
    renderApp("/perfil");

    await userEvent.click(await screen.findByRole("button", { name: "Quero excluir minha conta" }));
    const form = screen.getByRole("form", { name: "Excluir minha conta" });
    await userEvent.click(within(form).getByRole("button", { name: "Excluir minha conta" }));
    expect(within(form).getByText("Digite EXCLUIR para confirmar.")).toBeInTheDocument();
    expect(writes().some((call) => call.path === "/api/v1/accounts/me")).toBe(false);

    await userEvent.type(within(form).getByLabelText(/Para confirmar/), "excluir");
    await userEvent.click(within(form).getByRole("button", { name: "Excluir minha conta" }));

    await waitFor(() => expect(authState.signoutRedirect).toHaveBeenCalled());
    expect(writes().some((call) => call.method === "DELETE" && call.path === "/api/v1/accounts/me")).toBe(true);
  });

  it("explains when the account is being deleted", async () => {
    vi.stubGlobal("fetch", async () =>
      new Response(JSON.stringify({ type: "about:blank", title: "Forbidden", status: 403, code: "ACCOUNT_NOT_ACTIVE", correlationId: "c" }), { status: 403 }));
    renderApp();

    expect(await screen.findByRole("heading", { name: "Sua conta está sendo excluída" })).toBeInTheDocument();
    expect(screen.queryByRole("navigation", { name: "Principal" })).not.toBeInTheDocument();
  });

  it("opens the assistant, with the reminder that it does not diagnose", async () => {
    renderApp("/assistente");

    expect(await screen.findByRole("heading", { name: "Assistente" })).toBeInTheDocument();
    expect(screen.getByText("não faz diagnóstico")).toBeInTheDocument();
  });
});

describe("fetchReadiness", () => {
  it("reports DOWN when the API is unreachable", async () => {
    const failing = vi.fn(async () => {
      throw new TypeError("network error");
    });
    expect(await fetchReadiness(failing as unknown as typeof fetch)).toBe("DOWN");
  });

  it("reports DOWN on a non-UP body", async () => {
    const down = vi.fn(async () => new Response(JSON.stringify({ status: "DOWN" }), { status: 503 }));
    expect(await fetchReadiness(down as unknown as typeof fetch)).toBe("DOWN");
  });
});
