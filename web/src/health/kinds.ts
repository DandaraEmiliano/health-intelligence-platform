import {
  IconAlertTriangle,
  IconApple,
  IconLeaf,
  IconPill,
  IconQuestionMark,
  IconStethoscope,
  type Icon,
} from "@tabler/icons-react";

import type { HealthRecordKind } from "../api/resources";
import type { ChipOption } from "../ui/ChipChoice";
import { formatDate } from "../ui/format";
import type { Tone } from "../navigation";

export type FieldSpec =
  | { name: string; label: string; type: "text"; required?: boolean; maxLength: number; placeholder?: string; optional?: boolean }
  | { name: string; label: string; type: "textarea"; maxLength: number; placeholder?: string; optional?: boolean }
  | { name: string; label: string; type: "date"; optional?: boolean }
  | { name: string; label: string; type: "chips"; required?: boolean; options: readonly ChipOption<string>[]; optional?: boolean };

export type FormValues = Record<string, string>;

export interface KindConfig {
  kind: HealthRecordKind;
  /** Tab label, plural. */
  label: string;
  /** "Adicionar alergia". */
  addLabel: string;
  icon: Icon;
  emptyTitle: string;
  emptyBody: string;
  fields: readonly FieldSpec[];
  title: (record: FormValues) => string;
  subtitle: (record: FormValues) => string;
  /** Colour of the record card: attention colours only where they mean attention. */
  tone: (record: FormValues) => Tone;
  pill?: (record: FormValues) => string | undefined;
  /** Rules the form checks before sending (the API checks again). */
  validate?: (values: FormValues, today: string) => Record<string, string>;
}

const ALLERGY_TYPES: readonly ChipOption<string>[] = [
  { value: "MEDICATION", label: "Remédio", icon: IconPill },
  { value: "FOOD", label: "Alimento", icon: IconApple },
  { value: "ENVIRONMENTAL", label: "Ambiente", icon: IconLeaf },
  { value: "OTHER", label: "Outro", icon: IconQuestionMark },
];
const SEVERITIES: readonly ChipOption<string>[] = [
  { value: "MILD", label: "Leve" },
  { value: "MODERATE", label: "Moderada" },
  { value: "SEVERE", label: "Grave" },
  { value: "UNKNOWN", label: "Não sei" },
];
const CONDITION_STATUS: readonly ChipOption<string>[] = [
  { value: "ACTIVE", label: "Ativa" },
  { value: "RESOLVED", label: "Resolvida" },
  { value: "UNKNOWN", label: "Não sei" },
];

const labelOf = (options: readonly ChipOption<string>[], value: string) =>
  options.find((option) => option.value === value)?.label ?? "";

function notInFuture(values: FormValues, today: string, ...fields: string[]) {
  const errors: Record<string, string> = {};
  for (const field of fields) {
    if (values[field] && values[field] > today) errors[field] = "A data não pode estar no futuro.";
  }
  return errors;
}

export const KINDS: Record<HealthRecordKind, KindConfig> = {
  allergies: {
    kind: "allergies",
    label: "Alergias",
    addLabel: "Adicionar alergia",
    icon: IconAlertTriangle,
    emptyTitle: "Registre suas alergias",
    emptyBody: "Assim elas ficam à mão quando você precisar mostrar a um profissional de saúde.",
    fields: [
      { name: "substance", label: "A que você tem alergia?", type: "text", required: true, maxLength: 200, placeholder: "Dipirona" },
      { name: "allergyType", label: "Tipo", type: "chips", required: true, options: ALLERGY_TYPES },
      { name: "severity", label: "Intensidade", type: "chips", options: SEVERITIES, optional: true },
      { name: "notes", label: "Observações", type: "textarea", maxLength: 1000, placeholder: "O que acontece, desde quando", optional: true },
    ],
    title: (r) => r.substance,
    subtitle: (r) => [labelOf(ALLERGY_TYPES, r.allergyType), r.notes].filter(Boolean).join(" · "),
    tone: (r) => (r.severity === "SEVERE" ? "coral" : r.severity === "MODERATE" ? "amber" : "lilac"),
    pill: (r) => (r.severity ? labelOf(SEVERITIES, r.severity) : undefined),
  },
  medications: {
    kind: "medications",
    label: "Remédios",
    addLabel: "Adicionar remédio",
    icon: IconPill,
    emptyTitle: "Quais remédios você usa?",
    emptyBody: "Anote como você toma cada um. A plataforma nunca sugere nem altera doses.",
    fields: [
      { name: "name", label: "Nome", type: "text", required: true, maxLength: 200, placeholder: "Losartana" },
      { name: "dosageText", label: "Dose", type: "text", maxLength: 200, placeholder: "50 mg", optional: true },
      { name: "frequencyText", label: "Como você toma", type: "text", maxLength: 200, placeholder: "1 vez ao dia, de manhã", optional: true },
      { name: "startedOn", label: "Começou em", type: "date", optional: true },
      { name: "endedOn", label: "Parou em", type: "date", optional: true },
    ],
    title: (r) => r.name,
    subtitle: (r) =>
      [r.dosageText, r.frequencyText, r.startedOn && `desde ${formatDate(r.startedOn)}`].filter(Boolean).join(" · "),
    tone: (r) => (r.endedOn ? "gray" : "blue"),
    pill: (r) => (r.endedOn ? `Parou em ${formatDate(r.endedOn)}` : "Em uso"),
    validate: (values, today) => {
      const errors = notInFuture(values, today, "startedOn", "endedOn");
      if (values.startedOn && values.endedOn && values.endedOn < values.startedOn) {
        errors.endedOn = "O fim precisa ser depois do início.";
      }
      return errors;
    },
  },
  conditions: {
    kind: "conditions",
    label: "Condições",
    addLabel: "Adicionar condição",
    icon: IconStethoscope,
    emptyTitle: "Tem alguma condição de saúde?",
    emptyBody: "Registre diagnósticos que você já recebeu, como hipertensão ou asma.",
    fields: [
      { name: "description", label: "Condição", type: "text", required: true, maxLength: 500, placeholder: "Hipotireoidismo" },
      { name: "status", label: "Situação", type: "chips", required: true, options: CONDITION_STATUS },
      { name: "onsetDateApprox", label: "Começou por volta de", type: "date", optional: true },
    ],
    title: (r) => r.description,
    subtitle: (r) => (r.onsetDateApprox ? `desde ${formatDate(r.onsetDateApprox)}` : ""),
    tone: (r) => (r.status === "ACTIVE" ? "pink" : r.status === "RESOLVED" ? "teal" : "gray"),
    pill: (r) => labelOf(CONDITION_STATUS, r.status),
    validate: (values, today) => notInFuture(values, today, "onsetDateApprox"),
  },
};

export const KIND_ORDER: readonly HealthRecordKind[] = ["allergies", "medications", "conditions"];

/** API record → flat string values for the form and the card. */
export function toFormValues(config: KindConfig, record: Record<string, unknown> | undefined): FormValues {
  const values: FormValues = {};
  for (const field of config.fields) {
    const value = record?.[field.name];
    values[field.name] = value === undefined || value === null ? "" : String(value);
  }
  return values;
}

/** Form values → request body; empty optional fields are left out. */
export function toRequest(config: KindConfig, values: FormValues): Record<string, string> {
  const body: Record<string, string> = {};
  for (const field of config.fields) {
    const value = values[field.name]?.trim() ?? "";
    if (value) body[field.name] = value;
  }
  return body;
}

export function validateRecord(config: KindConfig, values: FormValues, today: string): Record<string, string> {
  const errors: Record<string, string> = {};
  for (const field of config.fields) {
    if ("required" in field && field.required && !values[field.name]?.trim()) {
      errors[field.name] = field.type === "chips" ? "Escolha uma opção." : "Preencha este campo.";
    }
  }
  return { ...(config.validate?.(values, today) ?? {}), ...errors };
}
