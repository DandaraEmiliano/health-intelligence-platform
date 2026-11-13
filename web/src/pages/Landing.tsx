import { useAuth } from "react-oidc-context";

export function Landing() {
  const auth = useAuth();

  return (
    <main className="landing">
      <h1>Health Intelligence Platform</h1>
      <p className="lead">
        Seus exames laboratoriais organizados, verificados por você e explicados em linguagem simples.
      </p>
      <ul className="principles">
        <li>Você revisa cada resultado extraído antes que ele seja usado.</li>
        <li>As explicações citam a fonte e não substituem a avaliação de um profissional de saúde.</li>
        <li>Seus dados nunca são compartilhados com outros pacientes.</li>
      </ul>
      {auth.error && (
        <p role="alert" className="error">
          Não foi possível conectar ao serviço de login. Tente novamente em instantes.
        </p>
      )}
      <div className="actions">
        <button type="button" className="primary" onClick={() => void auth.signinRedirect()}>
          Entrar
        </button>
        <button type="button" onClick={() => void auth.signinRedirect({ prompt: "create" })}>
          Criar conta
        </button>
      </div>
    </main>
  );
}
