import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactNode } from "react";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { acceptedConsents, fakeFetch, fakeState, patientRecord, type FakeApiState } from "../test/fakeApi";
import { NotificationsPage } from "./NotificationsPage";

let api: FakeApiState;

function renderAt(path = "/avisos") {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}>{children}</MemoryRouter></QueryClientProvider>
  );
  return render(
    <Routes>
      <Route path="/avisos" element={<NotificationsPage />} />
      <Route path="/exames/:reportId" element={<p>Tela do exame</p>} />
    </Routes>,
    { wrapper },
  );
}

const notification = (id: string, type: string, status: string, resource: Record<string, string>) => ({
  id, type, status, resource, createdAt: "2026-11-20T10:00:00Z", readAt: status === "READ" ? "2026-11-20T11:00:00Z" : null,
});

describe("Avisos", () => {
  beforeEach(() => {
    api = fakeState({ consents: acceptedConsents(), patient: patientRecord() });
    vi.stubGlobal("fetch", fakeFetch(api));
  });

  it("says when there is nothing new", async () => {
    renderAt();

    expect(await screen.findByRole("heading", { name: "Nenhum aviso" })).toBeInTheDocument();
  });

  it("lists notifications, links a report to its review and marks it read on open", async () => {
    api.notifications = [
      notification("n-1", "REVIEW_PENDING", "UNREAD", { type: "LABORATORY_REPORT", id: "rep-1" }),
      notification("n-2", "EXTRACTION_FAILED", "READ", { type: "DOCUMENT", id: "doc-1" }),
    ];
    const user = userEvent.setup();
    renderAt();

    const list = await screen.findByRole("list", { name: "Seus avisos" });
    const review = within(list).getByRole("link", { name: /Laudo pronto para revisar/ });
    expect(review).toHaveAttribute("href", "/exames/rep-1");
    expect(within(review).getByText("Novo")).toBeInTheDocument();
    expect(within(list).getByRole("link", { name: /Não conseguimos ler um documento/ })).toHaveAttribute("href", "/documentos");

    await user.click(review);

    expect(await screen.findByText("Tela do exame")).toBeInTheDocument();
    expect(api.calls.some((c) => c.method === "POST" && c.path === "/api/v1/notifications/n-1/read")).toBe(true);
  });

  it("marks everything read at once", async () => {
    api.notifications = [
      notification("n-1", "REVIEW_PENDING", "UNREAD", { type: "LABORATORY_REPORT", id: "rep-1" }),
      notification("n-2", "DOCUMENT_UNSUPPORTED", "UNREAD", { type: "DOCUMENT", id: "doc-2" }),
    ];
    const user = userEvent.setup();
    renderAt();

    await user.click(await screen.findByRole("button", { name: "Marcar todos como lidos" }));

    await screen.findByRole("list", { name: "Seus avisos" });
    expect(await screen.findByText("Documento não suportado")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Marcar todos como lidos" })).not.toBeInTheDocument();
    expect(api.notifications.every((n) => n.status === "READ")).toBe(true);
  });
});
