/** Main navigation. Features arrive phase by phase, each with its backend and screen. */
export const NAV_ITEMS = [
  {
    path: "/documentos",
    label: "Documentos",
    phase: 4,
    description: "Envie laudos em PDF e acompanhe o processamento de cada um.",
  },
  {
    path: "/exames",
    label: "Exames",
    phase: 4,
    description: "Revise os resultados extraídos de cada laudo antes que sejam usados.",
  },
  {
    path: "/linha-do-tempo",
    label: "Linha do tempo",
    phase: 4,
    description: "Veja seus exames em ordem cronológica e compare datas.",
  },
  {
    path: "/assistente",
    label: "Assistente",
    phase: 5,
    description: "Pergunte sobre seus resultados verificados e receba explicações com fonte.",
  },
  {
    path: "/evolucao",
    label: "Evolução",
    phase: 6,
    description: "Acompanhe a evolução de cada exame ao longo do tempo.",
  },
] as const;
