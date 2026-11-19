import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactNode } from "react";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { acceptedConsents, fakeFetch, fakeState, patientRecord, pendingReport, type FakeApiState } from "../test/fakeApi";
import { measurementFrom } from "./values";
import { ExamsPage } from "./ExamsPage";
import { ReportReview } from "./ReportReview";

let api: FakeApiState;

function renderAt(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}>{children}</MemoryRouter></QueryClientProvider>
  );
  return render(
    <Routes>
      <Route path="/exames" element={<ExamsPage />} />
      <Route path="/exames/:reportId" element={<ReportReview />} />
    </Routes>,
    { wrapper },
  );
}

const reviews = () => api.calls.filter((c) => c.method === "POST" && c.path.endsWith("/review"));

describe("Exames", () => {
  beforeEach(() => {
    api = fakeState({ consents: acceptedConsents(), patient: patientRecord() });
    api.reports["rep-1"] = pendingReport();
    vi.stubGlobal("fetch", fakeFetch(api));
  });

  it("lists reports with what is left to review", async () => {
    renderAt("/exames");

    const link = await screen.findByRole("link", { name: /Laboratório Sintético Alfa/ });
    expect(link).toHaveAttribute("href", "/exames/rep-1");
    expect(within(link).getByText("2 para revisar")).toBeInTheDocument();
    expect(within(link).getByText(/Coleta em 10\/11\/2026/)).toBeInTheDocument();
  });

  it("shows each result with its source, flag and warnings", async () => {
    renderAt("/exames/rep-1");

    const item = (await screen.findByText("Glicose")).closest("li")!;
    expect(within(item).getByText("Acima da referência")).toBeInTheDocument();
    expect(within(item).getByText(/página 1/)).toBeInTheDocument();
    expect(screen.getByText(/faixas diferentes por grupo/)).toBeInTheDocument();
    expect(screen.getByText(/não são um diagnóstico/)).toBeInTheDocument();
  });

  it("confirms a result with the current ETag", async () => {
    renderAt("/exames/rep-1");

    const group = await screen.findByRole("group", { name: "Decisão sobre Glicose" });
    await userEvent.click(within(group).getByRole("button", { name: /Está certo/ }));

    expect(await screen.findByText("Confirmado")).toBeInTheDocument();
    expect(reviews()[0].headers["if-match"]).toBe('"0"');
    expect(reviews()[0].body).toEqual({ decisions: [{ resultId: "res-1", action: "CONFIRM" }] });
  });

  it("corrects a value typed as printed and keeps the original visible", async () => {
    renderAt("/exames/rep-1");

    const group = await screen.findByRole("group", { name: "Decisão sobre Colesterol HDL" });
    await userEvent.click(within(group).getByRole("button", { name: /Corrigir/ }));
    const form = screen.getByRole("form", { name: "Corrigir Colesterol HDL" });
    const value = within(form).getByLabelText("Valor");
    await userEvent.clear(value);
    await userEvent.type(value, "52,5");
    await userEvent.click(within(form).getByRole("button", { name: "Salvar correção" }));

    expect(await screen.findByText("Corrigido")).toBeInTheDocument();
    expect(screen.getByText(/Lido originalmente como “48”/)).toBeInTheDocument();
    const correction = (reviews()[0].body as { decisions: { correction: { measurement: unknown } }[] }).decisions[0].correction;
    expect(correction.measurement).toEqual({ kind: "NUMERIC", rawValue: "52,5", valueNumeric: "52.5" });
  });

  it("explains an incomplete review, then confirms the rest and finishes", async () => {
    renderAt("/exames/rep-1");

    await userEvent.click(await screen.findByRole("button", { name: "Concluir revisão" }));
    expect(await screen.findByText(/Ainda há resultados sem decisão/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Confirmar os 2 restantes" }));
    await userEvent.click(await screen.findByRole("button", { name: "Concluir revisão" }));

    expect(await screen.findByText(/Revisão concluída em/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Está certo/ })).not.toBeInTheDocument();
    expect(reviews().at(-1)?.headers["if-match"]).toBe('"1"');
  });
});

describe("measurementFrom", () => {
  it("reads values as printed in Brazilian reports", () => {
    expect(measurementFrom("1,25")).toEqual({ kind: "NUMERIC", rawValue: "1,25", valueNumeric: "1.25" });
    expect(measurementFrom("245.000")).toEqual({ kind: "NUMERIC", rawValue: "245.000", valueNumeric: "245000" });
    expect(measurementFrom("< 0,5")).toEqual({ kind: "NUMERIC", rawValue: "< 0,5", valueNumeric: "0.5", comparator: "<" });
    expect(measurementFrom("Não reagente")).toEqual({ kind: "TEXT", rawValue: "Não reagente", valueText: "Não reagente" });
  });
});
