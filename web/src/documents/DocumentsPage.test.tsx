import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactNode } from "react";
import { MemoryRouter } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { acceptedConsents, fakeFetch, FakeXhr, fakeState, patientRecord, type FakeApiState } from "../test/fakeApi";
import { DocumentsPage } from "./DocumentsPage";

let api: FakeApiState;

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>{children}</MemoryRouter>
    </QueryClientProvider>
  );
  return render(<DocumentsPage />, { wrapper });
}

const pdf = (name = "hemograma.pdf", size = 2048) => new File([new Uint8Array(size)], name, { type: "application/pdf" });

describe("DocumentsPage", () => {
  beforeEach(() => {
    api = fakeState({ consents: acceptedConsents(), patient: patientRecord() });
    vi.stubGlobal("fetch", fakeFetch(api));
    FakeXhr.sent = [];
    FakeXhr.respond = () => {
      const document = { id: `d-${FakeXhr.sent.length}`, originalFilename: "hemograma.pdf", sizeBytes: 2048, status: "PROCESSING", uploadedAt: "2026-11-18T12:00:00Z" };
      api.documents.unshift(document);
      return { status: 202, body: document };
    };
    vi.stubGlobal("XMLHttpRequest", FakeXhr);
  });

  it("uploads a chosen PDF with an idempotency key and lists it", async () => {
    renderPage();
    expect(await screen.findByText("Você ainda não enviou nenhum laudo.")).toBeInTheDocument();

    await userEvent.upload(screen.getByLabelText("Escolher arquivos PDF"), pdf());

    expect(await screen.findByText("Enviado")).toBeInTheDocument();
    expect(FakeXhr.sent).toHaveLength(1);
    expect(FakeXhr.sent[0].url).toMatch(/\/api\/v1\/documents$/);
    expect(FakeXhr.sent[0].headers["idempotency-key"]).toMatch(/^[0-9a-f-]{36}$/);
    const list = await screen.findByRole("list", { name: "Seus documentos" });
    expect(within(list).getByText("hemograma.pdf")).toBeInTheDocument();
    expect(within(list).getByText("Na fila para leitura")).toBeInTheDocument();
  });

  it("checks type and size before sending", async () => {
    renderPage();
    await screen.findByText("Você ainda não enviou nenhum laudo.");

    await userEvent.upload(screen.getByLabelText("Escolher arquivos PDF"), [
      new File(["x"], "foto.jpg", { type: "image/jpeg" }),
      pdf("grande.pdf", 10 * 1024 * 1024 + 1),
    ], { applyAccept: false });

    expect(await screen.findByText("Este arquivo não é um PDF.")).toBeInTheDocument();
    expect(screen.getByText("O arquivo passa de 10 MB.")).toBeInTheDocument();
    expect(FakeXhr.sent).toHaveLength(0);
  });

  it("explains what the server rejected", async () => {
    FakeXhr.respond = () => ({ status: 409, body: { type: "about:blank", title: "Conflict", status: 409, code: "DUPLICATE_DOCUMENT", correlationId: "c" } });
    renderPage();
    await screen.findByText("Você ainda não enviou nenhum laudo.");

    await userEvent.upload(screen.getByLabelText("Escolher arquivos PDF"), pdf());

    expect(await screen.findByText("Você já enviou este mesmo arquivo.")).toBeInTheDocument();
  });

  it("deletes a document after confirmation", async () => {
    api.documents.push({ id: "d-9", originalFilename: "antigo.pdf", sizeBytes: 1000, status: "UPLOADED", uploadedAt: "2026-10-01T12:00:00Z" });
    renderPage();

    await userEvent.click(await screen.findByRole("button", { name: "Excluir antigo.pdf" }));
    await userEvent.click(screen.getByRole("button", { name: "Sim, excluir" }));

    expect(await screen.findByText("Você ainda não enviou nenhum laudo.")).toBeInTheDocument();
    expect(api.calls.some((call) => call.method === "DELETE" && call.path === "/api/v1/documents/d-9")).toBe(true);
  });

  it("offers the fictitious example reports for download", async () => {
    renderPage();
    const links = await screen.findAllByRole("link", { name: /Laudo de exemplo/ });
    expect(links.map((link) => link.getAttribute("href"))).toEqual([
      "/exemplos/laudo-exemplo-2025-09-20.pdf",
      "/exemplos/laudo-exemplo-2026-03-21.pdf",
      "/exemplos/laudo-exemplo-2026-09-19.pdf",
    ]);
    expect(links.every((link) => link.hasAttribute("download"))).toBe(true);
  });

  it("says when AI reading is off", async () => {
    api.consents = acceptedConsents(false);
    renderPage();

    expect(await screen.findByText(/A leitura por IA está desligada/)).toBeInTheDocument();
  });
});
