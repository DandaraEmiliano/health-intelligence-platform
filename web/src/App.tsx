import type { ReactNode } from "react";
import { useAuth } from "react-oidc-context";
import { NavLink, Route, Routes } from "react-router";

import { NAV_ITEMS } from "./navigation";
import { ComingSoon } from "./pages/ComingSoon";
import { Home } from "./pages/Home";
import { Landing } from "./pages/Landing";


function NotFound() {
  return (
    <section>
      <h1>Página não encontrada</h1>
      <NavLink to="/">Voltar ao início</NavLink>
    </section>
  );
}

export function RequireAuth({ children }: { children: ReactNode }) {
  const auth = useAuth();
  if (auth.isLoading) return <p className="loading">Carregando…</p>;
  if (!auth.isAuthenticated) return <Landing />;
  return <>{children}</>;
}

function Shell({ children }: { children: ReactNode }) {
  const auth = useAuth();
  return (
    <div className="shell">
      <header className="topbar">
        <NavLink to="/" className="brand">
          Health Intelligence
        </NavLink>
        <nav aria-label="Principal">
          {NAV_ITEMS.map((item) => (
            <NavLink key={item.path} to={item.path}>
              {item.label}
            </NavLink>
          ))}
        </nav>
        <button type="button" onClick={() => void auth.signoutRedirect()}>
          Sair
        </button>
      </header>
      <div className="content">{children}</div>
    </div>
  );
}

export function App() {
  return (
    <>
      <p className="env-banner" role="note">
        Ambiente de desenvolvimento: use somente dados sintéticos.
      </p>
      <RequireAuth>
        <Shell>
          <Routes>
            <Route path="/" element={<Home />} />
            {NAV_ITEMS.map((item) => (
              <Route
                key={item.path}
                path={item.path}
                element={<ComingSoon title={item.label} phase={item.phase} description={item.description} />}
              />
            ))}
            <Route path="*" element={<NotFound />} />
          </Routes>
        </Shell>
      </RequireAuth>
    </>
  );
}
