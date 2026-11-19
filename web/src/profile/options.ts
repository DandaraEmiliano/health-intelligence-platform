import type { BiologicalSex } from "../api/resources";
import type { ChipOption } from "../ui/ChipChoice";

export const SEX_OPTIONS: readonly ChipOption<BiologicalSex>[] = [
  { value: "FEMALE", label: "Feminino" },
  { value: "MALE", label: "Masculino" },
  { value: "INTERSEX", label: "Intersexo" },
  { value: "UNDISCLOSED", label: "Prefiro não informar" },
];

export function sexLabel(value: BiologicalSex | undefined): string {
  return SEX_OPTIONS.find((option) => option.value === value)?.label ?? "Não informado";
}
