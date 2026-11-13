import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactNode } from "react";
import { MemoryRouter } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";

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

function renderApp(path = "/") {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>{children}</MemoryRouter>
    </QueryClientProvider>
  );
  return render(<App />, { wrapper });
}

describe("App", () => {
  beforeEach(() => {
    Object.assign(authState, { isLoading: false, isAuthenticated: false, error: undefined, user: undefined });
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ status: "UP" }), { status: 200 })));
  });

  it("shows the landing page and starts sign-in when signed out", async () => {
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

  it("shows the app shell and greets the user when signed in", async () => {
    Object.assign(authState, {
      isAuthenticated: true,
      user: { access_token: "t", profile: { given_name: "Paciente", email: "patient.a@example.test" } },
    });

    renderApp();

    expect(screen.getByRole("heading", { name: "Olá, Paciente" })).toBeInTheDocument();
    expect(screen.getByRole("navigation", { name: "Principal" })).toBeInTheDocument();
    expect(await screen.findByText("disponível")).toBeInTheDocument();
  });

  it("shows what a planned feature will do, without fake data", () => {
    Object.assign(authState, { isAuthenticated: true, user: { access_token: "t", profile: {} } });

    renderApp("/exames");

    expect(screen.getByRole("heading", { name: "Exames" })).toBeInTheDocument();
    expect(screen.getByText("Planejado para a Fase 4.")).toBeInTheDocument();
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
