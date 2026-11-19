import { useState, type FormEvent } from "react";

import type { LaboratoryResult, ReviewRequest } from "../api/resources";
import { Field } from "../ui/Field";
import { decimalString, formatDecimal, measurementFrom } from "./values";

type Correction = NonNullable<ReviewRequest["decisions"][number]["correction"]>;

/** The patient types values as printed in the report ("1,25", "< 0,5"); the form converts them. */
export function CorrectionForm({ result, pending, onSubmit, onCancel }: {
  result: LaboratoryResult;
  pending: boolean;
  onSubmit: (correction: Correction) => void;
  onCancel: () => void;
}) {
  const [value, setValue] = useState(result.measurement.rawValue);
  const [unit, setUnit] = useState(result.unit ?? "");
  const [range, setRange] = useState(result.referenceRange.rawText ?? "");
  const [low, setLow] = useState(formatDecimal(result.referenceRange.low));
  const [high, setHigh] = useState(formatDecimal(result.referenceRange.high));
  const [errors, setErrors] = useState<Record<string, string>>({});

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const found: Record<string, string> = {};
    if (!value.trim()) found.value = "Preencha o valor.";
    const lowValue = low.trim() ? decimalString(low) : null;
    const highValue = high.trim() ? decimalString(high) : null;
    if (low.trim() && lowValue === null) found.low = "Use um número, como 70 ou 0,5.";
    if (high.trim() && highValue === null) found.high = "Use um número, como 99 ou 1,2.";
    if (lowValue !== null && highValue !== null && Number(lowValue) > Number(highValue)) found.high = "O máximo precisa ser maior que o mínimo.";
    setErrors(found);
    if (Object.keys(found).length) return;
    onSubmit({
      measurement: measurementFrom(value),
      unit: unit.trim() || null,
      referenceRange: {
        rawText: range.trim() || null,
        low: lowValue,
        high: highValue,
        appliesTo: result.referenceRange.appliesTo ?? null,
      },
    });
  };

  return (
    <form className="form-grid correction" onSubmit={submit} noValidate aria-label={`Corrigir ${result.rawName}`}>
      <p className="hint">Digite como está no laudo. O valor original lido fica guardado.</p>
      <div className="form-row">
        <Field label="Valor" error={errors.value}>
          {(props) => <input {...props} value={value} onChange={(e) => setValue(e.target.value)} />}
        </Field>
        <Field label="Unidade" hint="opcional">
          {(props) => <input {...props} value={unit} placeholder="mg/dL" onChange={(e) => setUnit(e.target.value)} />}
        </Field>
      </div>
      <Field label="Referência como no laudo" hint="opcional">
        {(props) => <input {...props} value={range} placeholder="70 a 99" onChange={(e) => setRange(e.target.value)} />}
      </Field>
      <div className="form-row">
        <Field label="Mínimo da referência" hint="opcional" error={errors.low}>
          {(props) => <input {...props} inputMode="decimal" value={low} onChange={(e) => setLow(e.target.value)} />}
        </Field>
        <Field label="Máximo da referência" hint="opcional" error={errors.high}>
          {(props) => <input {...props} inputMode="decimal" value={high} onChange={(e) => setHigh(e.target.value)} />}
        </Field>
      </div>
      <div className="form-actions">
        <button type="button" className="ghost" onClick={onCancel}>Cancelar</button>
        <button type="submit" className="primary" aria-busy={pending}>Salvar correção</button>
      </div>
    </form>
  );
}
