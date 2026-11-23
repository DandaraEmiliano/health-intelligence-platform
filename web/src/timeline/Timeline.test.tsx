import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import type { ReactNode } from "react";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { AnalyteSeries, SeriesPoint, TrackedAnalyte } from "../api/resources";
import { acceptedConsents, fakeFetch, fakeState, patientRecord, type FakeApiState } from "../test/fakeApi";
import { AnalyteDetail } from "./AnalyteDetail";
import { TimelinePage } from "./TimelinePage";

let api: FakeApiState;

function renderAt(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}>{children}</MemoryRouter></QueryClientProvider>
  );
  return render(
    <Routes>
      <Route path="/linha-do-tempo" element={<TimelinePage />} />
      <Route path="/linha-do-tempo/:analyteId" element={<AnalyteDetail />} />
    </Routes>,
    { wrapper },
  );
}

const glucose = { id: "an-glu", code: "GLUCOSE", displayName: "Glicose", category: "Glicemia" };
const potassium = { id: "an-k", code: "K", displayName: "Potássio", category: "Eletrólitos" };

function point(collectedAt: string, value: string, extra: Partial<SeriesPoint> = {}): SeriesPoint {
  return {
    resultId: `r-${collectedAt}-${value}`, reportId: `rep-${collectedAt}`, collectedAt, value, unit: "mg/dL",
    referenceRange: { rawText: "70 a 99", low: "70", high: "99", appliesTo: null },
    flag: { value: "IN_RANGE", source: "COMPUTED" }, laboratoryName: "Laboratório Sintético Alfa", ...extra,
  };
}

const tracked: TrackedAnalyte[] = [
  {
    analyte: glucose, count: 3,
    latest: point("2026-03-11", "112", { flag: { value: "HIGH", source: "LABORATORY" } }),
    recent: [{ collectedAt: "2024-09-11", value: "87" }, { collectedAt: "2026-03-11", value: "112" }],
  },
  { analyte: potassium, count: 1, latest: point("2026-03-11", "4.1", { unit: "mmol/L" }), recent: [{ collectedAt: "2026-03-11", value: "4.1" }] },
];

describe("Linha do tempo", () => {
  beforeEach(() => {
    api = fakeState({ consents: acceptedConsents(), patient: patientRecord() });
    vi.stubGlobal("fetch", fakeFetch(api));
  });

  it("invites to finish a review when nothing is verified yet", async () => {
    renderAt("/linha-do-tempo");

    expect(await screen.findByRole("heading", { name: "Sua evolução aparece aqui" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Ir para Exames" })).toHaveAttribute("href", "/exames");
  });

  it("groups analytes by category with their latest value, in Brazilian format", async () => {
    api.tracked = tracked;
    renderAt("/linha-do-tempo");

    const glicemia = await screen.findByRole("list", { name: "Glicemia" });
    const card = within(glicemia).getByRole("link", { name: /Glicose/ });
    expect(card).toHaveAttribute("href", "/linha-do-tempo/an-glu");
    expect(within(card).getByText("Fora da referência")).toBeInTheDocument();
    expect(within(card).getByText(/11\/03\/2026 · 3 resultados/)).toBeInTheDocument();
    const k = within(screen.getByRole("list", { name: "Eletrólitos" })).getByRole("link", { name: /Potássio/ });
    expect(within(k).getByText(/4,1/)).toBeInTheDocument();
    expect(within(k).queryByText("Fora da referência")).not.toBeInTheDocument();
  });

  it("lists collections by collection date and asks only for laboratory reports", async () => {
    api.tracked = tracked;
    api.timeline = [
      { type: "LABORATORY_REPORT", id: "rep-2026", occurredOn: "2026-03-11", title: "Beta Diagnósticos Sintéticos", status: "VERIFIED", resultCount: 12, outOfRangeCount: 2 },
      { type: "LABORATORY_REPORT", id: "rep-2024", occurredOn: "2024-09-11", title: "Gama Análises Clínicas", status: "VERIFIED", resultCount: 14, outOfRangeCount: 0 },
    ];
    renderAt("/linha-do-tempo");

    const first = await screen.findByRole("link", { name: /Beta Diagnósticos Sintéticos · 12 resultados, 2 fora da referência/ });
    expect(first).toHaveAttribute("href", "/exames/rep-2026");
    expect(screen.getByRole("link", { name: /Gama Análises Clínicas · 14 resultados$/ })).toBeInTheDocument();
    const call = api.calls.find((c) => c.path === "/api/v1/health-timeline");
    expect(new URLSearchParams(call?.search).get("types")).toBe("LABORATORY_REPORT");
  });

  it("shows each unit apart, never converted, newest first in the table", async () => {
    const series: AnalyteSeries = {
      analyte: glucose,
      series: [
        { unit: "mg/dL", points: [point("2024-09-11", "87"), point("2026-03-11", "92")] },
        { unit: "mmol/L", points: [point("2023-03-15", "4.8", { unit: "mmol/L", comparator: undefined, laboratoryName: "Delta" })] },
      ],
    };
    api.series["an-glu"] = series;
    renderAt("/linha-do-tempo/an-glu");

    expect(await screen.findByRole("heading", { name: "Glicose" })).toBeInTheDocument();
    expect(screen.getByText(/unidades diferentes/)).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Em mg/dL" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Em mmol/L" })).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "Gráfico com 2 resultados em mg/dL" })).toBeInTheDocument();
    const rows = within(screen.getAllByRole("table")[0]).getAllByRole("row").slice(1);
    expect(rows.map((r) => within(r).getAllByRole("cell")[0].textContent)).toEqual(["11/03/2026", "11/09/2024"]);
    expect(within(rows[0]).getByText("Dentro (calculado)")).toBeInTheDocument();
    expect(within(screen.getAllByRole("table")[1]).getByText(/4,8 mmol\/L/)).toBeInTheDocument();
  });

  it("marks censored values as bounds", async () => {
    api.series["an-crp"] = {
      analyte: { id: "an-crp", code: "CRP_HS", displayName: "PCR ultrassensível", category: "Inflamação" },
      series: [{ unit: "mg/L", points: [point("2024-01-01", "0.5", { unit: "mg/L", comparator: "<" }), point("2025-01-01", "1.2", { unit: "mg/L" })] }],
    };
    const { container } = renderAt("/linha-do-tempo/an-crp");

    expect(within(await screen.findByRole("table")).getByText("< 0,5 mg/L")).toBeInTheDocument();
    expect(container.querySelectorAll("circle.censored")).toHaveLength(1);
  });

  it("explains a missing analyte", async () => {
    renderAt("/linha-do-tempo/desconhecido");

    expect(await screen.findByRole("alert")).toBeInTheDocument();
  });
});
