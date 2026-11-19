import type { Icon } from "@tabler/icons-react";

export interface ChipOption<V extends string> {
  value: V;
  label: string;
  icon?: Icon;
}

/** Radio group shown as chips. Keyboard and screen readers get native radio behaviour. */
export function ChipChoice<V extends string>({ legend, name, options, value, onChange, error, allowClear }: {
  legend: string;
  name: string;
  options: readonly ChipOption<V>[];
  value: V | "";
  onChange: (value: V | "") => void;
  error?: string;
  allowClear?: boolean;
}) {
  return (
    <fieldset aria-invalid={error ? true : undefined}>
      <legend>{legend}</legend>
      <div className="chips">
        {options.map((option) => {
          const Icon = option.icon;
          return (
            <label key={option.value} className="chip-option">
              <input
                type="radio"
                name={name}
                value={option.value}
                checked={value === option.value}
                onChange={() => onChange(option.value)}
                onClick={() => {
                  if (allowClear && value === option.value) onChange("");
                }}
              />
              <span>
                {Icon && <Icon size={16} aria-hidden="true" />}
                {option.label}
              </span>
            </label>
          );
        })}
      </div>
      {error && <span className="field-error">{error}</span>}
    </fieldset>
  );
}
