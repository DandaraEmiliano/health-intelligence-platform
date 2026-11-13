import { useQuery } from "@tanstack/react-query";
import { useAuth } from "react-oidc-context";

import { fetchReadiness } from "../api/health";

export function Home() {
  const auth = useAuth();
  const readiness = useQuery({ queryKey: ["core-api-readiness"], queryFn: () => fetchReadiness() });
  const name = auth.user?.profile.given_name ?? auth.user?.profile.email ?? "";

  return (
    <section>
      <h1>Olá{name ? `, ${name}` : ""}</h1>
      <p>
        Esta é a base do aplicativo. As funcionalidades chegam por etapas, cada uma com backend e tela
        juntos.
      </p>
      <div className="card" aria-live="polite">
        <h2>Status do sistema</h2>
        {readiness.isPending ? (
          <p>Verificando…</p>
        ) : (
          <p>
            API principal:{" "}
            <strong className={readiness.data === "UP" ? "ok" : "down"}>
              {readiness.data === "UP" ? "disponível" : "indisponível"}
            </strong>
          </p>
        )}
      </div>
    </section>
  );
}
