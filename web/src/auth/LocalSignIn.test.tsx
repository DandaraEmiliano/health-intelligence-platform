import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { LocalSignIn } from "./LocalSignIn";

interface Call {
  path: string;
  body: unknown;
  csrf: string | null;
}

function stubFetch(answer: (path: string) => { status: number; body?: unknown }) {
  const calls: Call[] = [];
  vi.stubGlobal("fetch", async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    const headers = new Headers(init?.headers);
    calls.push({ path, body: init?.body ? JSON.parse(String(init.body)) : undefined, csrf: headers.get("X-XSRF-TOKEN") });
    const { status, body } = answer(path);
    return new Response(body === undefined ? null : JSON.stringify(body), { status });
  });
  return calls;
}

function problem(status: number, code: string, errors?: unknown[]) {
  return { type: "about:blank", title: code, status, code, correlationId: "c", ...(errors ? { errors } : {}) };
}

function renderForm() {
  const client = new QueryClient();
  const invalidate = vi.spyOn(client, "invalidateQueries");
  render(
    <QueryClientProvider client={client}>
      <LocalSignIn />
    </QueryClientProvider>,
  );
  return invalidate;
}

async function fill(email: string, password: string) {
  await userEvent.type(screen.getByLabelText("E-mail"), email);
  await userEvent.type(screen.getByLabelText(/Senha/), password);
}

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT";
});

describe("LocalSignIn", () => {
  it("signs in with the CSRF token and refreshes the session", async () => {
    document.cookie = "XSRF-TOKEN=token-1";
    const calls = stubFetch(() => ({ status: 200, body: { email: "ana@example.test", name: null } }));
    const invalidate = renderForm();

    await fill("ana@example.test", "uma senha longa 123");
    await userEvent.click(screen.getAllByRole("button", { name: "Entrar" }).at(-1)!);

    expect(calls[0]).toEqual({ path: "/bff/login", body: { email: "ana@example.test", password: "uma senha longa 123" }, csrf: "token-1" });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["session"] });
  });

  it("says the same thing for a wrong password and an unknown e-mail", async () => {
    stubFetch(() => ({ status: 401, body: problem(401, "INVALID_CREDENTIALS") }));
    renderForm();

    await fill("ana@example.test", "errada errada");
    await userEvent.click(screen.getAllByRole("button", { name: "Entrar" }).at(-1)!);

    expect(await screen.findByRole("alert")).toHaveTextContent("E-mail ou senha incorretos.");
  });

  it("creates an account and explains the rules when they are broken", async () => {
    const answers = [
      { status: 400, body: problem(400, "VALIDATION_FAILED", [{ field: "password", code: "TOO_WEAK", message: "x" }]) },
      { status: 409, body: problem(409, "EMAIL_ALREADY_REGISTERED") },
    ];
    const calls = stubFetch(() => answers.shift()!);
    renderForm();

    await userEvent.click(screen.getByRole("button", { name: "Criar conta" }));
    await fill("ana@example.test", "ana12345678");
    await userEvent.click(screen.getAllByRole("button", { name: "Criar conta" }).at(-1)!);
    expect(await screen.findByRole("alert")).toHaveTextContent("A senha precisa ter de 10 a 128 caracteres");
    expect(calls[0].path).toBe("/bff/register");

    await userEvent.click(screen.getAllByRole("button", { name: "Criar conta" }).at(-1)!);
    expect(await screen.findByRole("alert")).toHaveTextContent("Já existe uma conta com este e-mail");
  });

  it("asks to wait after too many attempts", async () => {
    stubFetch(() => ({ status: 429, body: problem(429, "RATE_LIMITED") }));
    renderForm();

    await fill("ana@example.test", "qualquer senha");
    await userEvent.click(screen.getAllByRole("button", { name: "Entrar" }).at(-1)!);

    expect(await screen.findByRole("alert")).toHaveTextContent("Muitas tentativas");
  });
});
