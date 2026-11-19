import type { FieldErrors } from "../api/messages";
import type { BiologicalSex, Patient, PatientCreate, PatientUpdate } from "../api/resources";
import { todayIso } from "../ui/format";

export interface ProfileValues {
  fullName: string;
  birthDate: string;
  biologicalSex: BiologicalSex | "";
  heightCm: string;
}

export function valuesOf(patient: Patient | undefined): ProfileValues {
  return {
    fullName: patient?.fullName ?? "",
    birthDate: patient?.birthDate ?? "",
    biologicalSex: patient?.biologicalSex ?? "",
    heightCm: patient?.heightCm === undefined ? "" : String(patient.heightCm),
  };
}

/** Checks done before calling the API; the server checks again and has the final word. */
export function validateProfile(values: ProfileValues, today = todayIso()): FieldErrors {
  const errors: FieldErrors = {};
  if (!values.fullName.trim()) errors.fullName = "Preencha este campo.";
  if (!values.birthDate) errors.birthDate = "Preencha este campo.";
  else if (values.birthDate >= today) errors.birthDate = "A data precisa estar no passado.";
  if (values.heightCm) {
    const height = Number(values.heightCm.replace(",", "."));
    if (!Number.isFinite(height) || height < 30 || height > 272) errors.heightCm = "Informe a altura em cm, entre 30 e 272.";
  }
  return errors;
}


const height = (value: string) => (value ? Number(value.replace(",", ".")) : undefined);

export function toCreateRequest(values: ProfileValues): PatientCreate {
  return {
    fullName: values.fullName.trim(),
    birthDate: values.birthDate,
    biologicalSex: values.biologicalSex || undefined,
    heightCm: height(values.heightCm),
  };
}

/** JSON Merge Patch with only what changed; cleared optional fields become null. */
export function toMergePatch(current: Patient, values: ProfileValues): PatientUpdate {
  const patch: PatientUpdate = {};
  if (values.fullName.trim() !== current.fullName) patch.fullName = values.fullName.trim();
  if (values.birthDate !== current.birthDate) patch.birthDate = values.birthDate;
  if ((values.biologicalSex || undefined) !== current.biologicalSex) patch.biologicalSex = values.biologicalSex || null;
  const newHeight = height(values.heightCm);
  if (newHeight !== current.heightCm) patch.heightCm = newHeight ?? null;
  return patch;
}
