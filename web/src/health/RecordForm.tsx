import { useState, type FormEvent } from "react";

import { fieldErrors, problemMessage } from "../api/messages";
import { ChipChoice } from "../ui/ChipChoice";
import { Field } from "../ui/Field";
import { todayIso } from "../ui/format";
import { validateRecord, type FormValues, type KindConfig } from "./kinds";

export function RecordForm({ config, initial, title, pending, error, onSubmit, onCancel }: {
  config: KindConfig;
  initial: FormValues;
  title: string;
  pending: boolean;
  error: unknown;
  onSubmit: (values: FormValues) => void;
  onCancel: () => void;
}) {
  const [values, setValues] = useState(initial);
  const [localErrors, setLocalErrors] = useState<Record<string, string>>({});
  const errors = { ...fieldErrors(error), ...localErrors };
  const banner = problemMessage(error);

  const set = (name: string, value: string) => {
    setValues((current) => ({ ...current, [name]: value }));
    setLocalErrors((current) => ({ ...current, [name]: "" }));
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const found = validateRecord(config, values, todayIso());
    setLocalErrors(found);
    if (Object.values(found).some(Boolean)) return;
    onSubmit(values);
  };

  return (
    <form className="card form-grid" onSubmit={submit} noValidate aria-label={title}>
      <h3>{title}</h3>
      {banner && <p className="error-box" role="alert">{banner}</p>}
      {config.fields.map((field) => {
        const error = errors[field.name];
        if (field.type === "chips") {
          return (
            <ChipChoice
              key={field.name}
              legend={field.label}
              name={`${config.kind}-${field.name}`}
              options={field.options}
              value={values[field.name] ?? ""}
              onChange={(value) => set(field.name, value)}
              error={error}
              allowClear={field.optional}
            />
          );
        }
        return (
          <Field key={field.name} label={field.label} hint={field.optional ? "opcional" : undefined} error={error}>
            {(props) =>
              field.type === "textarea" ? (
                <textarea
                  {...props}
                  value={values[field.name] ?? ""}
                  maxLength={field.maxLength}
                  placeholder={field.placeholder}
                  onChange={(event) => set(field.name, event.target.value)}
                />
              ) : (
                <input
                  {...props}
                  type={field.type === "date" ? "date" : "text"}
                  value={values[field.name] ?? ""}
                  maxLength={field.type === "text" ? field.maxLength : undefined}
                  max={field.type === "date" ? todayIso() : undefined}
                  placeholder={field.type === "text" ? field.placeholder : undefined}
                  onChange={(event) => set(field.name, event.target.value)}
                />
              )
            }
          </Field>
        );
      })}
      <div className="form-actions">
        <button type="button" className="ghost" onClick={onCancel}>Cancelar</button>
        <button type="submit" className="primary" aria-busy={pending}>{pending ? "Salvando…" : "Salvar"}</button>
      </div>
    </form>
  );
}
