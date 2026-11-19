import { describe, expect, it } from "vitest";

import { acceptedConsents } from "../test/fakeApi";
import { pendingSteps } from "./onboardingSteps";

describe("pendingSteps", () => {
  it("asks a new user for everything", () => {
    expect(pendingSteps([], false)).toEqual(["terms", "ai", "profile"]);
  });

  it("accepts either answer to the AI question", () => {
    expect(pendingSteps(acceptedConsents(false), true)).toEqual([]);
  });

  it("asks again when the terms version changed", () => {
    const consents = acceptedConsents().map((c) => (c.type === "TERMS_OF_USE" ? { ...c, documentVersion: "2025.1" } : c));
    expect(pendingSteps(consents, true)).toEqual(["terms"]);
  });
});
