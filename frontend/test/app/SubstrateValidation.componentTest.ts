import { describe, expect, it } from "vitest";
import { validateSubstrate } from "../../src/app/SubstrateFields";
import { substrateComponentId } from "../../src/domain/Journal";

const perlite = substrateComponentId("00000000-0000-4000-8000-000000000003");
const bark = substrateComponentId("00000000-0000-4000-8000-000000000004");

describe("validateSubstrate", () => {
  it("should accept unique components whose shares stay within 100%", () => {
    expect(
      validateSubstrate([
        { component: perlite, share: 70 },
        { component: bark, share: 30 },
      ]),
    ).toBeUndefined();
  });

  it("should reject each way a mix can be invalid", () => {
    expect(validateSubstrate([])).toBe("Add at least one substrate component.");
    expect(validateSubstrate([{ component: perlite, share: 0 }])).toBe(
      "Each substrate share must be a whole number from 1 to 100%.",
    );
    expect(validateSubstrate([{ component: perlite, share: 1.5 }])).toBe(
      "Each substrate share must be a whole number from 1 to 100%.",
    );
    expect(
      validateSubstrate([
        { component: perlite, share: 50 },
        { component: perlite, share: 50 },
      ]),
    ).toBe("Each substrate component can only be used once.");
    expect(
      validateSubstrate([
        { component: perlite, share: 60 },
        { component: bark, share: 50 },
      ]),
    ).toBe("Substrate shares cannot total more than 100%.");
  });
});
