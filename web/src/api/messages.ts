import { ApiProblemError } from "./client";

/** Field error codes from core-api, in plain Portuguese. Never shows submitted values. */
const FIELD_MESSAGES: Record<string, string> = {
  REQUIRED: "Preencha este campo.",
  INVALID_LENGTH: "Texto mais longo que o permitido.",
  PAST_DATE_REQUIRED: "A data precisa estar no passado.",
  FUTURE_DATE_NOT_ALLOWED: "A data não pode estar no futuro.",
  OUT_OF_RANGE: "Valor fora do intervalo aceito.",
  INVALID_PRECISION: "Use no máximo uma casa decimal.",
  INVALID_PERIOD: "O fim precisa ser depois do início.",
  INVALID_FORMAT: "Formato inválido.",
  INVALID_VALUE: "Escolha uma das opções.",
  INVALID_TYPE: "Formato inválido.",
};

const PROBLEM_MESSAGES: Record<string, string> = {
  PRECONDITION_FAILED: "Isto mudou enquanto você editava (talvez em outra aba). Recarregamos os dados; confira e tente de novo.",
  RECORD_LIMIT_REACHED: "Você chegou ao limite de registros deste tipo.",
  PATIENT_ALREADY_EXISTS: "Seu perfil já foi criado.",
  MALFORMED_REQUEST: "Algo no formulário não foi aceito. Confira os campos.",
  UNAUTHORIZED: "Sua sessão expirou. Entre de novo.",
  FORBIDDEN: "Sua conta não tem acesso a esta área.",
  DUPLICATE_DOCUMENT: "Você já enviou este mesmo arquivo.",
  UNSUPPORTED_DOCUMENT: "Este arquivo não é um PDF.",
  DOCUMENT_ENCRYPTED: "Este PDF tem senha. Envie uma versão sem senha.",
  DOCUMENT_TOO_LARGE: "O arquivo passa de 10 MB.",
  DOCUMENT_NOT_FOUND: "Este documento não existe mais.",
  REQUEST_IN_PROGRESS: "Este envio ainda está em andamento.",
  REVIEW_INCOMPLETE: "Ainda há resultados sem decisão. Confirme, corrija ou rejeite todos antes de concluir.",
  INVALID_STATE: "Este laudo já foi revisado e não pode mais ser alterado.",
  LABORATORY_REPORT_NOT_FOUND: "Este laudo não existe mais.",
};

export type FieldErrors = Record<string, string>;

export function fieldErrors(error: unknown): FieldErrors {
  if (!(error instanceof ApiProblemError) || !error.problem.errors) return {};
  const result: FieldErrors = {};
  for (const item of error.problem.errors) {
    result[item.field] = FIELD_MESSAGES[item.code] ?? "Valor inválido.";
  }
  return result;
}

/** One sentence for a banner, or undefined when the field errors already say everything. */
export function problemMessage(error: unknown): string | undefined {
  if (error instanceof ApiProblemError) {
    if (error.problem.code === "VALIDATION_FAILED") return undefined;
    return PROBLEM_MESSAGES[error.problem.code] ?? "Não foi possível concluir agora. Tente de novo em instantes.";
  }
  return error ? "Não conseguimos falar com o servidor. Verifique a conexão e tente de novo." : undefined;
}
