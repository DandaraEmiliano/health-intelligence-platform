import { IconHeart, IconShieldCheck, IconSparkles, IconUserCircle } from "@tabler/icons-react";
import { useState } from "react";

import { problemMessage } from "../api/messages";
import { useCreatePatient, useRecordConsents } from "../api/queries";
import { CONSENT_VERSIONS } from "../api/resources";
import { ProfileForm } from "../profile/ProfileForm";
import { toCreateRequest, valuesOf } from "../profile/values";
import type { OnboardingStep } from "./onboardingSteps";

export function Onboarding({ steps }: { steps: OnboardingStep[] }) {
  // The total is fixed at the start so the progress dots do not shrink as steps are completed.
  const [total] = useState(steps.length);
  const step = steps[0];
  const index = total - steps.length;

  return (
    <main className="onboarding">
      <div className="card">
        <div className="steps" aria-label={`Etapa ${index + 1} de ${total}`}>
          {Array.from({ length: total }, (_, i) => (
            <span key={i} className={i < index ? "done" : i === index ? "current" : ""} />
          ))}
        </div>
        {step === "terms" && <TermsStep />}
        {step === "ai" && <AiStep />}
        {step === "profile" && <ProfileStep />}
      </div>
    </main>
  );
}

function TermsStep() {
  const [terms, setTerms] = useState(false);
  const [privacy, setPrivacy] = useState(false);
  const [tried, setTried] = useState(false);
  const record = useRecordConsents();
  const ready = terms && privacy;

  return (
    <div className="stack">
      <div className="stack center tone-pink">
        <span className="bubble large" aria-hidden="true"><IconHeart size={32} stroke={1.6} /></span>
        <h1>Boas-vindas</h1>
        <p className="muted">
          Aqui você guarda seus exames, confere cada resultado e entende o que eles dizem. Antes de começar,
          precisamos do seu acordo com duas coisas.
        </p>
      </div>
      <label className="check">
        <input type="checkbox" checked={terms} onChange={(event) => setTerms(event.target.checked)} />
        <span>Li e aceito os <strong>Termos de uso</strong> (versão {CONSENT_VERSIONS.TERMS_OF_USE}).</span>
      </label>
      <label className="check">
        <input type="checkbox" checked={privacy} onChange={(event) => setPrivacy(event.target.checked)} />
        <span>Li e aceito a <strong>Política de privacidade</strong> (versão {CONSENT_VERSIONS.PRIVACY_POLICY}). Meus dados de saúde nunca são mostrados a outras pessoas.</span>
      </label>
      {tried && !ready && <p className="field-error" role="alert">Marque as duas opções para continuar.</p>}
      {record.isError && <p className="error-box" role="alert">{problemMessage(record.error)}</p>}
      <button
        className="primary"
        aria-busy={record.isPending}
        onClick={() => {
          setTried(true);
          if (!ready) return;
          record.mutate([
            { type: "TERMS_OF_USE", granted: true },
            { type: "PRIVACY_POLICY", granted: true },
          ]);
        }}
      >
        Concordo e continuar
      </button>
    </div>
  );
}

function AiStep() {
  const record = useRecordConsents();
  return (
    <div className="stack">
      <div className="stack center tone-pink">
        <span className="bubble large" aria-hidden="true"><IconSparkles size={32} stroke={1.6} /></span>
        <h1>A IA pode ler seus laudos?</h1>
        <p className="muted">
          Ela tira os resultados dos PDFs que você enviar e organiza tudo para você. Nada é usado antes de você
          conferir, ela não dá diagnósticos e você pode desligar quando quiser em Meu perfil.
        </p>
      </div>
      <ul className="principles tone-lilac">
        <li><IconShieldCheck size={20} aria-hidden="true" /> Só os seus dados, nunca os de outras pessoas</li>
        <li><IconUserCircle size={20} aria-hidden="true" /> Você revisa cada resultado antes de qualquer uso</li>
      </ul>
      {record.isError && <p className="error-box" role="alert">{problemMessage(record.error)}</p>}
      <div className="form-row">
        <button aria-busy={record.isPending} onClick={() => record.mutate([{ type: "AI_PROCESSING", granted: false }])}>
          Agora não
        </button>
        <button className="primary" aria-busy={record.isPending} onClick={() => record.mutate([{ type: "AI_PROCESSING", granted: true }])}>
          Sim, pode ler
        </button>
      </div>
    </div>
  );
}

function ProfileStep() {
  const create = useCreatePatient();
  return (
    <div className="stack">
      <div className="stack center tone-lilac">
        <span className="bubble large" aria-hidden="true"><IconUserCircle size={32} stroke={1.6} /></span>
        <h1>Conte um pouco sobre você</h1>
        <p className="muted">Usamos esses dados para organizar seus exames. Você pode mudar depois.</p>
      </div>
      <ProfileForm
        initial={valuesOf(undefined)}
        submitLabel="Criar meu perfil"
        pending={create.isPending}
        error={create.error}
        onSubmit={(values) => create.mutate(toCreateRequest(values))}
      />
    </div>
  );
}
