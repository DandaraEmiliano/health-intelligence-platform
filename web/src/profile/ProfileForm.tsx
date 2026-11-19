import { useState, type FormEvent } from "react";

import { fieldErrors, problemMessage, type FieldErrors } from "../api/messages";
import { ChipChoice } from "../ui/ChipChoice";
import { Field } from "../ui/Field";
import { todayIso } from "../ui/format";
import { SEX_OPTIONS } from "./options";
import { validateProfile, type ProfileValues } from "./values";

export function ProfileForm({ initial, submitLabel, onSubmit, onCancel, pending, error }: {
  initial: ProfileValues;
  submitLabel: string;
  onSubmit: (values: ProfileValues) => void;
  onCancel?: () => void;
  pending: boolean;
  error: unknown;
}) {
  const [values, setValues] = useState(initial);
  const [localErrors, setLocalErrors] = useState<FieldErrors>({});
  const errors = { ...fieldErrors(error), ...localErrors };
  const banner = problemMessage(error);

  const set = <F extends keyof ProfileValues>(field: F, value: ProfileValues[F]) => {
    setValues((current) => ({ ...current, [field]: value }));
    setLocalErrors((current) => ({ ...current, [field]: "" }));
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const found = validateProfile(values);
    setLocalErrors(found);
    if (Object.values(found).some(Boolean)) return;
    onSubmit(values);
  };

  return (
    <form className="form-grid" onSubmit={submit} noValidate>
      {banner && <p className="error-box" role="alert">{banner}</p>}
      <Field label="Nome completo" error={errors.fullName}>
        {(props) => (
          <input {...props} value={values.fullName} onChange={(event) => set("fullName", event.target.value)} maxLength={200} autoComplete="name" />
        )}
      </Field>
      <div className="form-row">
        <Field label="Data de nascimento" error={errors.birthDate}>
          {(props) => (
            <input {...props} type="date" value={values.birthDate} max={todayIso()} onChange={(event) => set("birthDate", event.target.value)} />
          )}
        </Field>
        <Field label="Altura (cm)" hint="opcional" error={errors.heightCm}>
          {(props) => (
            <input {...props} inputMode="decimal" placeholder="165" value={values.heightCm} onChange={(event) => set("heightCm", event.target.value)} />
          )}
        </Field>
      </div>
      <ChipChoice
        legend="Sexo biológico (opcional, ajuda a ler valores de referência)"
        name="biologicalSex"
        options={SEX_OPTIONS}
        value={values.biologicalSex}
        onChange={(value) => set("biologicalSex", value)}
        error={errors.biologicalSex}
        allowClear
      />
      <div className="form-actions">
        {onCancel && <button type="button" className="ghost" onClick={onCancel}>Cancelar</button>}
        <button type="submit" className="primary" aria-busy={pending}>{pending ? "Salvando…" : submitLabel}</button>
      </div>
    </form>
  );
}
