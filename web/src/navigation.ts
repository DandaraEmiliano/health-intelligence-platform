import {
  IconFileUpload,
  IconFirstAidKit,
  IconHome,
  IconSparkles,
  IconTestPipe,
  IconTimeline,
  IconUser,
  type Icon,
} from "@tabler/icons-react";

export type Tone = "lilac" | "blue" | "amber" | "teal" | "coral" | "pink" | "gray";

export interface NavItem {
  path: string;
  label: string;
  tone: Tone;
  icon: Icon;
  /** Planned items show what they will do and when, never fake data. */
  plannedPhase?: number;
  description: string;
}

/** Main navigation. Each area owns one hue, repeated in its page header and cards. */
export const NAV_ITEMS: readonly NavItem[] = [
  { path: "/", label: "Início", tone: "lilac", icon: IconHome, description: "Resumo da sua saúde." },
  {
    path: "/documentos",
    label: "Documentos",
    tone: "blue",
    icon: IconFileUpload,
    plannedPhase: 4,
    description: "Envie laudos em PDF e acompanhe o processamento de cada um.",
  },
  {
    path: "/exames",
    label: "Exames",
    tone: "amber",
    icon: IconTestPipe,
    plannedPhase: 4,
    description: "Revise os resultados extraídos de cada laudo antes que sejam usados.",
  },
  {
    path: "/linha-do-tempo",
    label: "Linha do tempo",
    tone: "teal",
    icon: IconTimeline,
    plannedPhase: 4,
    description: "Veja seus exames em ordem cronológica e compare datas.",
  },
  {
    path: "/minha-saude",
    label: "Minha saúde",
    tone: "coral",
    icon: IconFirstAidKit,
    description: "Alergias, remédios e condições que você informa.",
  },
  {
    path: "/assistente",
    label: "Assistente",
    tone: "pink",
    icon: IconSparkles,
    plannedPhase: 5,
    description: "Pergunte sobre seus resultados verificados e receba explicações com fonte.",
  },
  { path: "/perfil", label: "Meu perfil", tone: "gray", icon: IconUser, description: "Seus dados e consentimentos." },
];

export function navItem(path: string): NavItem {
  const item = NAV_ITEMS.find((candidate) => candidate.path === path);
  if (!item) throw new Error(`Unknown navigation path ${path}`);
  return item;
}
