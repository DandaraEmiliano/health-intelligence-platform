import { IconAlertTriangle, IconBook, IconChevronRight, IconStethoscope } from "@tabler/icons-react";
import { Link } from "react-router";

import type { AssistantMessage } from "../api/resources";
import { formatDate } from "../ui/format";

const CARE: Record<string, { text: string; tone: string } | undefined> = {
  EMERGENCY: {
    text: "Procure atendimento de emergência agora: ligue 192 (SAMU) ou vá ao pronto-socorro mais próximo.",
    tone: "coral",
  },
  SOON: { text: "Recomendamos procurar um médico em breve para conversar sobre isso.", tone: "amber" },
  ROUTINE: { text: "Vale conversar com seu médico sobre isso na próxima consulta.", tone: "blue" },
};

const REFUSAL: Record<string, string> = {
  DIAGNOSIS_REQUEST: "O assistente não faz diagnósticos.",
  PRESCRIPTION_REQUEST: "O assistente não indica remédios.",
  DOSAGE_CHANGE_REQUEST: "O assistente não orienta mudanças de dose. Fale com quem prescreveu.",
  OUT_OF_SCOPE: "Esta pergunta está fora do que o assistente pode responder.",
};

/** "Uncertainty" is about the answer; shown to the patient as confidence. */
const CONFIDENCE: Record<string, string> = { LOW: "Confiança alta", MEDIUM: "Confiança média", HIGH: "Confiança baixa" };

/** One structured answer: text, then the facts it used (each linked to its exam), then explanations. */
export function AnswerView({ message }: { message: AssistantMessage }) {
  const care = message.seekProfessionalCare.recommended || message.seekProfessionalCare.urgency === "EMERGENCY"
    ? CARE[message.seekProfessionalCare.urgency]
    : undefined;
  const refusal = message.safety.refused && message.safety.reasonCode ? REFUSAL[message.safety.reasonCode] : undefined;
  const questions = message.suggestedQuestionsForDoctor ?? [];

  return (
    <article className="message assistant" aria-label="Resposta do assistente">
      {care && message.seekProfessionalCare.urgency === "EMERGENCY" && (
        <p className="care tone-coral" role="alert">
          <IconAlertTriangle size={18} aria-hidden="true" /> {care.text}
        </p>
      )}
      <p className="answer">{message.answer}</p>
      {refusal && <p className="hint">{refusal}</p>}
      {message.insufficientData && (
        <p className="hint">Seus dados verificados não são suficientes para responder isso com segurança.</p>
      )}

      {message.facts.length > 0 && (
        <section aria-label="Dados usados nesta resposta">
          <h3 className="section-label">Dados usados nesta resposta</h3>
          <ul className="facts">
            {message.facts.map((fact) => {
              const body = (
                <>
                  <span className="body">
                    <span className="title">{fact.label}: {fact.value}{fact.unit ? ` ${fact.unit}` : ""}</span>
                    <span className="sub">
                      {fact.collectedAt ? `Coleta em ${formatDate(fact.collectedAt)}` : "Data da coleta não informada"}
                      {fact.referenceRange ? ` · Referência: ${fact.referenceRange}` : ""}
                    </span>
                  </span>
                  {fact.reportId && <IconChevronRight size={16} aria-hidden="true" />}
                </>
              );
              return (
                <li key={fact.resultId}>
                  {fact.reportId ? (
                    <Link className="fact" to={`/exames/${fact.reportId}`} aria-label={`Ver exame: ${fact.label}`}>{body}</Link>
                  ) : (
                    <span className="fact">{body}</span>
                  )}
                </li>
              );
            })}
          </ul>
        </section>
      )}

      {message.explanations.length > 0 && (
        <section aria-label="Explicações">
          <h3 className="section-label">Explicações</h3>
          {message.explanations.map((explanation, index) => (
            <p key={index} className="explanation">
              {explanation.text}{" "}
              {explanation.sources.some((s) => s.type === "GLOSSARY") && (
                <span className="pill"><IconBook size={13} aria-hidden="true" /> Glossário</span>
              )}
            </p>
          ))}
        </section>
      )}

      {care && message.seekProfessionalCare.urgency !== "EMERGENCY" && (
        <p className={`care tone-${care.tone}`}><IconStethoscope size={16} aria-hidden="true" /> {care.text}</p>
      )}

      {questions.length > 0 && (
        <section aria-label="Perguntas para levar ao médico">
          <h3 className="section-label">Perguntas para levar ao médico</h3>
          <ul className="doctor-questions">
            {questions.map((question) => <li key={question}>{question}</li>)}
          </ul>
        </section>
      )}

      <p className="hint meta">
        {CONFIDENCE[message.uncertainty]} · Gerado por IA, não é diagnóstico
      </p>
    </article>
  );
}
