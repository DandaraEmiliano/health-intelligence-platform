import { IconClipboardCheck, IconHeart, IconLock, IconMessageCircle } from "@tabler/icons-react";
import { LocalSignIn } from "../auth/LocalSignIn";
import { signIn } from "../auth/session";
import { config } from "../config";

export function Landing({ unavailable = false }: { unavailable?: boolean }) {

  return (
    <main className="landing">
      <span className="bubble solid large tone-pink" aria-hidden="true"><IconHeart size={32} stroke={1.8} /></span>
      <h1>Health Intelligence Platform</h1>
      <p className="lead">Seus exames laboratoriais organizados, verificados por você e explicados em linguagem simples.</p>
      <ul className="principles">
        <li className="tone-amber"><IconClipboardCheck size={20} aria-hidden="true" /> Você revisa cada resultado extraído antes que ele seja usado.</li>
        <li className="tone-lilac"><IconMessageCircle size={20} aria-hidden="true" /> As explicações citam a fonte e não substituem a avaliação de um profissional de saúde.</li>
        <li className="tone-teal"><IconLock size={20} aria-hidden="true" /> Seus dados nunca são compartilhados com outros pacientes.</li>
      </ul>
      {unavailable && (
        <p role="alert" className="error-box">Não foi possível conectar ao serviço de login. Tente novamente em instantes.</p>
      )}
      {config.authMode === "local" ? (
        <LocalSignIn />
      ) : (
        <div className="actions">
          <button type="button" className="primary" onClick={() => signIn()}>Entrar</button>
          <button type="button" onClick={() => signIn({ createAccount: true })}>Criar conta</button>
        </div>
      )}
    </main>
  );
}
