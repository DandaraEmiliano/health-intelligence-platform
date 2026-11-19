import type { ConsentState } from "../api/resources";
import { CONSENT_VERSIONS } from "../api/resources";

export type OnboardingStep = "terms" | "ai" | "profile";

/**
 * What the user still has to do before using the app: accept the current terms and privacy
 * policy, answer the AI question once (yes or no), and create the profile.
 */
export function pendingSteps(consents: readonly ConsentState[], hasProfile: boolean): OnboardingStep[] {
  const current = (type: ConsentState["type"]) => consents.find((consent) => consent.type === type);
  const accepted = (type: "TERMS_OF_USE" | "PRIVACY_POLICY") => {
    const consent = current(type);
    return consent?.granted === true && consent.documentVersion === CONSENT_VERSIONS[type];
  };
  const steps: OnboardingStep[] = [];
  if (!accepted("TERMS_OF_USE") || !accepted("PRIVACY_POLICY")) steps.push("terms");
  if (!current("AI_PROCESSING")) steps.push("ai");
  if (!hasProfile) steps.push("profile");
  return steps;
}
