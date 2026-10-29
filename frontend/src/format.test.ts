import { describe, expect, it } from "vitest";
import { documentState, money, date, localToday } from "./format";

describe("financial display", () => {
  it("distinguishes partial and fully settled documents", () => {
    expect(documentState(100, 0)).toBe("Open");
    expect(documentState(100, 40)).toBe("Partially paid");
    expect(documentState(100, 100)).toBe("Settled");
  });
  it("shows EUR, negative values and calendar dates", () => {
    expect(money(-2100.25)).toContain("2");
    expect(money(-2100.25)).toContain("\u20ac");
    expect(date("2026-10-29")).toBe("29 Oct");
    expect(localToday()).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });
});
