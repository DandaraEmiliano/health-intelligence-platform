import { useQueryClient } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";

import { problemCode, problemStatus } from "../api/http";
import { apiRequest } from "../api/http";
import { ApiProblemError } from "../api/client";
import { sessionKey } from "./session";

/** Built-in sign-in (Render profile): the session cookie is set by core-api, never seen here. */
export function LocalSignIn() {
  const client = useQueryClient();
  const [mode, setMode] = useState<"login" | "register">("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setBusy(true);
    try {
      await apiRequest(mode === "login" ? "/bff/login" : "/bff/register", { method: "POST", body: { email, password } });
      await client.invalidateQueries({ queryKey: sessionKey });
    } catch (e) {
      setError(signInMessage(e, mode));
    } finally {
      setBusy(false);
    }
  }

  const register = mode === "register";
  return (
    <form className="card stack local-sign-in" onSubmit={(event) => void submit(event)} aria-label={register ? "Criar conta" : "Entrar"}>
      <div className="chips" role="group" aria-label="Escolha">
        <button type="button" className={`small ${register ? "ghost" : "primary"}`} aria-pressed={!register} onClick={() => setMode("login")}>Entrar</button>
        <button type="button" className={`small ${register ? "primary" : "ghost"}`} aria-pressed={register} onClick={() => setMode("register")}>Criar conta</button>
      </div>
      <label>
        E-mail
        <input type="email" autoComplete="email" required value={email} onChange={(event) => setEmail(event.target.value)} />
      </label>
      <label>
        Senha
        <input
          type="password"
          autoComplete={register ? "new-password" : "current-password"}
          required
          minLength={register ? 10 : undefined}
          maxLength={128}
          value={password}
          onChange={(event) => setPassword(event.target.value)}
        />
        {register && <span className="hint">De 10 a 128 caracteres, sem repetir o seu e-mail.</span>}
      </label>
      {error && <p className="error-box" role="alert">{error}</p>}
      <button type="submit" className="primary" disabled={busy} aria-busy={busy}>
        {register ? "Criar conta" : "Entrar"}
      </button>
    </form>
  );
}

function signInMessage(error: unknown, mode: "login" | "register"): string {
  const code = problemCode(error);
  if (code === "INVALID_CREDENTIALS") return "E-mail ou senha incorretos.";
  if (code === "EMAIL_ALREADY_REGISTERED") return "Já existe uma conta com este e-mail. Use Entrar.";
  if (code === "RATE_LIMITED") return "Muitas tentativas. Espere alguns minutos e tente de novo.";
  if (code === "VALIDATION_FAILED" && error instanceof ApiProblemError) {
    const fields = (error.problem.errors ?? []).map((violation) => violation.field);
    if (fields.includes("email")) return "Digite um e-mail válido.";
    if (mode === "register") return "A senha precisa ter de 10 a 128 caracteres e não pode conter o seu e-mail.";
  }
  if (problemStatus(error) === 403) return "Sua sessão expirou. Recarregue a página e tente de novo.";
  return "Não foi possível entrar agora. Tente novamente em instantes.";
}
