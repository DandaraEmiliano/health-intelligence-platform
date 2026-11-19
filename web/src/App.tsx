import type { ReactNode } from "react";
import { useAuth } from "react-oidc-context";
import { NavLink, Route, Routes } from "react-router";

import { problemMessage } from "./api/messages";
import { useConsents, usePatient } from "./api/queries";
import { DocumentsPage } from "./documents/DocumentsPage";
import { HealthRecordsPage } from "./health/HealthRecordsPage";
import { NAV_ITEMS } from "./navigation";
import { ComingSoon } from "./pages/ComingSoon";
import { Home } from "./pages/Home";
import { Landing } from "./pages/Landing";
import { Onboarding } from "./pages/Onboarding";
import { pendingSteps } from "./pages/onboardingSteps";
import { Profile } from "./pages/Profile";
import { Shell } from "./Shell";

function NotFound() {
  return (
    <section className="stack">
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

/** Sends new users through onboarding (terms, AI question, profile) before the app. */
function RequireOnboarding({ children }: { children: ReactNode }) {
  const consents = useConsents();
  const patient = usePatient();
  if (consents.isPending || patient.isPending) return <p className="loading">Carregando…</p>;
  if (consents.isError || patient.isError) {
    return (
      <div className="onboarding">
        <p className="error-box" role="alert">
          {problemMessage(consents.error ?? patient.error)}{" "}
          <button className="small" onClick={() => { void consents.refetch(); void patient.refetch(); }}>Tentar de novo</button>
        </p>
      </div>
    );
  }
  const steps = pendingSteps(consents.data, patient.data !== null);
  if (steps.length > 0) return <Onboarding steps={steps} />;
  return <>{children}</>;
}

export function App() {
  return (
    <>
      <p className="env-banner" role="note">Ambiente de desenvolvimento: use somente dados sintéticos.</p>
      <RequireAuth>
        <RequireOnboarding>
          <Shell>
            <Routes>
              <Route path="/" element={<Home />} />
              <Route path="/documentos" element={<DocumentsPage />} />
              <Route path="/minha-saude" element={<HealthRecordsPage />} />
              <Route path="/perfil" element={<Profile />} />
              {NAV_ITEMS.filter((item) => item.plannedPhase).map((item) => (
                <Route key={item.path} path={item.path} element={<ComingSoon item={item} />} />
              ))}
              <Route path="*" element={<NotFound />} />
            </Routes>
          </Shell>
        </RequireOnboarding>
      </RequireAuth>
    </>
  );
}
