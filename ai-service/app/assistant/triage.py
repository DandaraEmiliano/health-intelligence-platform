"""Deterministic checks on the question, before any model call (threat model T04).

Clear-cut cases never reach the LLM: signs of an emergency get fixed guidance, and requests to
start, stop or change medicines get a fixed refusal. Patterns are deliberately conservative; the
model and the output checks handle the subtler cases (for example diagnosis requests).
"""

import re
import unicodedata
from dataclasses import dataclass


def _plain(text: str) -> str:
    stripped = unicodedata.normalize("NFD", text)
    return "".join(c for c in stripped if unicodedata.category(c) != "Mn").lower()


_EMERGENCY = [
    r"dor (forte |muito forte )?no peito",
    r"aperto no peito",
    r"falta de ar",
    r"nao consigo respirar",
    r"dificuldade (para|de) respirar",
    r"desmai",
    r"convuls",
    r"boca torta",
    r"perdi a forca",
    r"fraqueza (de|em|num|no) (um|uma) lado",
    r"nao consigo falar",
    r"sangramento (intenso|forte|que nao para)",
    r"vomitando sangue",
    r"quero me matar",
    r"pensando em (me matar|suicidio)",
    r"tirar minha (propria )?vida",
    r"\bsuicid",
    r"overdose",
    r"tomei (muitos|varios) comprimidos",
]
_DOSAGE = [
    r"(aumentar|diminuir|reduzir|dobrar|ajustar|mudar|trocar) (a |minha )?dose",
    r"(posso|devo) (parar|interromper|suspender|largar) (de tomar|o|a|meu|minha)",
    r"quantos? (mg|miligramas|comprimidos|gotas) (devo|posso|tenho que)",
    r"qual (a )?dose (devo|posso)",
]
_PRESCRIPTION = [
    r"(me )?(receita|prescreva|prescrever|indique|indica|recomende) (um|uma|algum|alguma)? ?"
    r"(remedio|medicamento|suplemento)",
    r"qual (remedio|medicamento|suplemento) (devo|posso|eu) (tomar|usar)",
    r"que (remedio|medicamento|suplemento) (devo|posso|eu) (tomar|usar)",
]


@dataclass(frozen=True)
class Triage:
    reason: str | None  # EMERGENCY_SIGNS, DOSAGE_CHANGE_REQUEST, PRESCRIPTION_REQUEST or None


def triage(question: str) -> Triage:
    text = _plain(question)
    if any(re.search(p, text) for p in _EMERGENCY):
        return Triage("EMERGENCY_SIGNS")
    if any(re.search(p, text) for p in _DOSAGE):
        return Triage("DOSAGE_CHANGE_REQUEST")
    if any(re.search(p, text) for p in _PRESCRIPTION):
        return Triage("PRESCRIPTION_REQUEST")
    return Triage(None)


EMERGENCY_ANSWER = (
    "O que você descreveu pode ser sinal de uma emergência. Ligue agora para o SAMU (192) ou vá ao pronto-socorro "
    "mais próximo. Se estiver com outra pessoa, peça ajuda a ela. Este assistente não substitui atendimento médico."
)
DOSAGE_ANSWER = (
    "Não posso orientar a começar, parar ou mudar a dose de nenhum medicamento. Essa decisão precisa ser tomada "
    "com o profissional de saúde que acompanha você, que conhece seu histórico. Se quiser, posso mostrar os "
    "resultados dos seus exames para você levar à consulta."
)
PRESCRIPTION_ANSWER = (
    "Não posso indicar remédios ou suplementos. Quem pode fazer isso é um profissional de saúde, depois de avaliar "
    "você. Se quiser, posso explicar os resultados dos seus exames para ajudar na conversa com ele."
)
FAILED_ANSWER = (
    "Desculpe, não consegui montar uma resposta segura com base nos seus dados agora. Tente reformular a pergunta "
    "ou converse com um profissional de saúde sobre seus resultados."
)
