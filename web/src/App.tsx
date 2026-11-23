import { AssistantPage } from "./assistant/AssistantPage";
import type { ReactNode } from "react";
import { useAuth } from "react-oidc-context";
import { NavLink, Route, Routes } from "react-router";

import { problemCode } from "./api/http";
import { problemMessage } from "./api/messages";
import { useConsents, usePatient } from "./api/queries";
import { DocumentsPage } from "./documents/DocumentsPage";
import { ExamsPage } from "./exams/ExamsPage";
import { ReportReview } from "./exams/ReportReview";
import { HealthRecordsPage } from "./health/HealthRecordsPage";
import { NAV_ITEMS } from "./navigation";
import { ComingSoon } from "./pages/ComingSoon";
import { Home } from "./pages/Home";
import { Landing } from "./pages/Landing";
import { Onboarding } from "./pages/Onboarding";
import { pendingSteps } from "./pages/onboardingSteps";
import { Profile } from "./pages/Profile";
import { Shell } from "./Shell";
import { AnalyteDetail } from "./timeline/AnalyteDetail";
import { TimelinePage } from "./timeline/TimelinePage";

function NotFound() {
  return (
    <section className="stack">
      <h1>Página não encontrada</h1>
      <NavLink to="/">Voltar ao início</NavLink>
    </section>
  );
}

function AccountBeingDeleted() {
  const auth = useAuth();
  return (
    <main className="onboarding">
      <div className="card stack center">
        <h1>Sua conta está sendo excluída</h1>
        <p className="muted">Recebemos seu pedido. Seus dados são apagados em alguns minutos e este login deixa de funcionar.</p>
        <button onClick={() => void auth.signoutRedirect()}>Sair</button>
      </div>
    </main>
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
  if (problemCode(consents.error ?? patient.error) === "ACCOUNT_NOT_ACTIVE") return <AccountBeingDeleted />;
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
              <Route path="/exames" element={<ExamsPage />} />
              <Route path="/exames/:reportId" element={<ReportReview />} />
              <Route path="/linha-do-tempo" element={<TimelinePage />} />
              <Route path="/linha-do-tempo/:analyteId" element={<AnalyteDetail />} />
              <Route path="/minha-saude" element={<HealthRecordsPage />} />
              <Route path="/assistente" element={<AssistantPage />} />
              <Route path="/assistente/:conversationId" element={<AssistantPage />} />
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
