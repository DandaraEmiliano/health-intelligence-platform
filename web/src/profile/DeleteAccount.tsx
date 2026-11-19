import { IconAlertTriangle } from "@tabler/icons-react";
import { useMutation } from "@tanstack/react-query";
import { useState } from "react";
import { useAuth } from "react-oidc-context";

import { problemMessage } from "../api/messages";
import { accountApi } from "../api/resources";
import { Field } from "../ui/Field";

const CONFIRMATION = "EXCLUIR";

/** Irreversible, so it explains what goes and asks for a typed confirmation. */
export function DeleteAccount() {
  const auth = useAuth();
  const [open, setOpen] = useState(false);
  const [typed, setTyped] = useState("");
  const [error, setError] = useState<string | undefined>();
  const request = useMutation({
    mutationFn: accountApi.requestDeletion,
    onSuccess: () => void auth.signoutRedirect(),
  });

  if (!open) {
    return (
      <div className="card danger-zone">
        <h2>Excluir minha conta</h2>
        <p className="muted">Apaga seu perfil, seus registros de saúde, seus laudos e seu login. Não dá para desfazer.</p>
        <div><button className="danger" onClick={() => setOpen(true)}>Quero excluir minha conta</button></div>
      </div>
    );
  }

  return (
    <form
      className="card danger-zone form-grid"
      aria-label="Excluir minha conta"
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        if (typed.trim().toUpperCase() !== CONFIRMATION) {
          setError(`Digite ${CONFIRMATION} para confirmar.`);
          return;
        }
        request.mutate();
      }}
    >
      <h2><IconAlertTriangle size={20} aria-hidden="true" /> Excluir minha conta</h2>
      <ul className="consequences">
        <li>Seu acesso é bloqueado na hora e você sai do aplicativo.</li>
        <li>Em alguns minutos apagamos seu perfil, alergias, remédios, condições, consentimentos, laudos em PDF e seu login.</li>
        <li>Guardamos só o registro de auditoria das operações, sem dados de saúde.</li>
        <li>Se quiser voltar depois, será preciso criar uma conta nova.</li>
      </ul>
      {request.isError && <p className="error-box" role="alert">{problemMessage(request.error)}</p>}
      <Field label={`Para confirmar, digite ${CONFIRMATION}`} error={error}>
        {(props) => (
          <input {...props} value={typed} autoComplete="off" onChange={(event) => { setTyped(event.target.value); setError(undefined); }} />
        )}
      </Field>
      <div className="form-actions">
        <button type="button" className="ghost" onClick={() => { setOpen(false); setTyped(""); setError(undefined); }}>Cancelar</button>
        <button type="submit" className="danger solid" aria-busy={request.isPending}>Excluir minha conta</button>
      </div>
    </form>
  );
}
