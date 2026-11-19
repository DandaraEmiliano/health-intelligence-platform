import { useId, type ReactElement, type ReactNode } from "react";

interface ControlProps {
  id?: string;
  "aria-invalid"?: boolean;
  "aria-describedby"?: string;
}

/**
 * Label, control and error kept apart: the label alone names the control, and the error is
 * announced as its description.
 */
export function Field({ label, hint, error, children }: {
  label: ReactNode;
  hint?: string;
  error?: string;
  children: (props: ControlProps) => ReactElement;
}) {
  const id = useId();
  const errorId = `${id}-error`;
  return (
    <div className="field">
      <label htmlFor={id}>
        {label}
        {hint && <span className="hint"> {hint}</span>}
      </label>
      {children({ id, "aria-invalid": error ? true : undefined, "aria-describedby": error ? errorId : undefined })}
      {error && <span id={errorId} className="field-error">{error}</span>}
    </div>
  );
}
