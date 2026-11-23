import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactNode } from "react";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { Home } from "../pages/Home";
import { acceptedConsents, fakeFetch, fakeState, patientRecord, pendingReport, type FakeApiState } from "../test/fakeApi";
import { ComparePage } from "./ComparePage";
import { OutOfRangePage } from "./OutOfRangePage";

let api: FakeApiState;

function renderAt(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}>{children}</MemoryRouter></QueryClientProvider>
  );
  return render(
    <Routes>
      <Route path="/" element={<Home />} />
      <Route path="/exames/comparar" element={<ComparePage />} />
      <Route path="/exames/fora-da-referencia" element={<OutOfRangePage />} />
      <Route path="/exames/:reportId" element={<p>Tela do exame</p>} />
    </Routes>,
    { wrapper },
  );
}

const glucose = { id: "an-glu", code: "GLUCOSE", displayName: "Glicose", category: "Glicemia" };

function result(id: string, reportId: string, value: string, unit: string, extra: Record<string, unknown> = {}) {
  return {
    id, reportId, sequence: 1, analyte: glucose, rawName: "GLICOSE",
    measurement: { kind: "NUMERIC", rawValue: value.replace(".", ","), valueNumeric: value },
    unit, referenceRange: { rawText: "70 a 99", low: "70", high: "99", appliesTo: null },
    flag: { value: "HIGH", source: "LABORATORY" }, collectedAt: "2026-03-11", reviewStatus: "VERIFIED",
    warnings: [], source: { documentId: "doc-1", page: 1, excerpt: "GLICOSE" }, ...extra,
  };
}

function verifiedReport(id: string, collectedAt: string) {
  const report = pendingReport(id);
  report.data.status = "VERIFIED";
  report.data.collectedAt = collectedAt;
  report.data.results.forEach((r) => { r.reviewStatus = "VERIFIED"; });
  return report;
}

describe("Resultados conferidos", () => {
  beforeEach(() => {
    api = fakeState({ consents: acceptedConsents(), patient: patientRecord() });
    vi.stubGlobal("fetch", fakeFetch(api));
  });

  it("summarises verified results on the home page, with recent out-of-range values", async () => {
    api.summary = {
      reportCount: 2, verifiedResultCount: 26, pendingReviewCount: 0, firstCollectedAt: "2022-03-16", lastCollectedAt: "2026-03-11",
      mostFrequentAnalytes: [glucose], recentOutOfRange: [result("r-1", "rep-2", "112", "mg/dL")],
    };
    renderAt("/");

    const card = await screen.findByRole("region", { name: "Seus exames conferidos" });
    expect(within(card).getByText(/26 resultados entre 16\/03\/2022 e 11\/03\/2026/)).toBeInTheDocument();
    expect(within(card).getByRole("link", { name: /Glicose: 112 mg\/dL/ })).toHaveAttribute("href", "/exames/rep-2");
    expect(within(card).getByRole("link", { name: "Ver todos fora da referência" })).toHaveAttribute("href", "/exames/fora-da-referencia");
  });

  it("lists out-of-range results with the laboratory flag and a reminder that it is not a diagnosis", async () => {
    api.outOfRange = [result("r-1", "rep-2", "112", "mg/dL")];
    renderAt("/exames/fora-da-referencia");

    const list = await screen.findByRole("list", { name: "Resultados fora da referência" });
    const item = within(list).getByRole("link", { name: /Glicose: 112 mg\/dL/ });
    expect(item).toHaveAttribute("href", "/exames/rep-2");
    expect(within(item).getByText("Acima da referência")).toBeInTheDocument();
    expect(screen.getByRole("note")).toHaveTextContent("não significa, sozinho, um problema de saúde");
  });

  it("compares two verified reports and never invents a difference between units", async () => {
    api.reports = { "rep-1": verifiedReport("rep-1", "2022-03-16"), "rep-2": verifiedReport("rep-2", "2023-03-15") };
    api.comparisons["rep-1|rep-2"] = {
      left: { id: "rep-1", documentId: "doc-1", laboratoryName: "Lab Alfa", collectedAt: "2022-03-16", status: "VERIFIED", resultCount: 2, pendingCount: 0 },
      right: { id: "rep-2", documentId: "doc-2", laboratoryName: "Lab Alfa", collectedAt: "2023-03-15", status: "VERIFIED", resultCount: 2, pendingCount: 0 },
      rows: [
        { analyte: glucose, label: "Glicose", left: result("a", "rep-1", "94", "mg/dL"), right: result("b", "rep-2", "4.8", "mmol/L"), delta: null, deltaUnavailableReason: "UNIT_MISMATCH" },
        { analyte: null, label: "HDL", left: result("c", "rep-1", "48", "mg/dL", { analyte: null, rawName: "HDL" }), right: result("d", "rep-2", "52.5", "mg/dL", { analyte: null, rawName: "HDL" }), delta: "4.5", deltaUnavailableReason: null },
        { analyte: null, label: "TSH", left: result("e", "rep-1", "2", "mUI/L"), right: null, delta: null, deltaUnavailableReason: "MISSING_SIDE" },
      ],
    };
    const user = userEvent.setup();
    renderAt("/exames/comparar");

    await user.selectOptions(await screen.findByLabelText("Laudo anterior"), "rep-1");
    await user.selectOptions(screen.getByLabelText("Laudo mais recente"), "rep-2");

    const table = await screen.findByRole("table");
    const glucoseRow = within(table).getByRole("row", { name: /Glicose/ });
    expect(glucoseRow).toHaveTextContent("94 mg/dL");
    expect(glucoseRow).toHaveTextContent("4,8 mmol/L");
    expect(glucoseRow).toHaveTextContent("Unidades diferentes, sem comparação");
    expect(within(table).getByRole("row", { name: /HDL/ })).toHaveTextContent("+4,5 mg/dL");
    expect(within(table).getByRole("row", { name: /TSH/ })).toHaveTextContent("Só em um dos laudos");
  });

  it("asks for two finished reviews before comparing", async () => {
    api.reports = { "rep-1": verifiedReport("rep-1", "2022-03-16") };
    renderAt("/exames/comparar");

    expect(await screen.findByText(/pelo menos dois laudos com a revisão concluída/)).toBeInTheDocument();
    expect(screen.queryByLabelText("Laudo anterior")).not.toBeInTheDocument();
  });
});
