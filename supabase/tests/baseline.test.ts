import { describe, expect, it } from "vitest";
import { nextBaseline } from "../functions/_shared/baseline.ts";
import { BASELINE_CASES } from "./fixtures.ts";

describe("nextBaseline (pure TS)", () => {
  for (const c of BASELINE_CASES) {
    it(c.label, () => {
      expect(nextBaseline(c.prev, c.samples, c.value, c.alpha)).toBeCloseTo(
        c.expected,
        12,
      );
    });
  }
});
