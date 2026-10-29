import { describe, expect, it } from "vitest";
import { eligibleDocuments, matchExplanation } from "./reconciliation";
import type { Document, Transaction } from "./types";

const payment: Transaction = {
  accountId: "EUR",
  externalId: "receipt",
  bookingDate: "2026-10-29",
  amount: 0.1,
  reference: "",
  counterparty: "Customer",
  documentId: null,
  matchMode: "AUTO",
  reviewRevision: 0,
  explanation: "MISSING_REFERENCE",
};
const document: Document = {
  id: "invoice",
  kind: "INVOICE",
  counterparty: "Customer",
  reference: "INV",
  amount: 0.3,
  paidAmount: 0.2,
  dueDate: "2026-11-01",
  expectedDate: null,
  cancelled: false,
  revision: 0,
};

describe("payment review eligibility", () => {
  it("uses cents for partial balances and never offers cancelled, settled or wrong-direction documents", () => {
    expect(
      eligibleDocuments(payment, [
        document,
        { ...document, id: "cancelled", cancelled: true },
        { ...document, id: "settled", paidAmount: 0.3 },
        { ...document, id: "small", amount: 0.29 },
        { ...document, id: "expense", kind: "EXPENSE" },
      ]),
    ).toEqual([document]);
  });
  it("supports outgoing whole-payment links and rejects zero values", () => {
    const expense: Document = { ...document, id: "expense", kind: "EXPENSE" };
    expect(
      eligibleDocuments({ ...payment, amount: -0.1 }, [document, expense]),
    ).toEqual([expense]);
    expect(
      eligibleDocuments({ ...payment, amount: 0 }, [document, expense]),
    ).toEqual([]);
  });
  it("clearly distinguishes an explicit hold from an unknown reference", () => {
    expect(matchExplanation.EXCLUDED_BY_USER).toContain("your decision");
    expect(matchExplanation.UNKNOWN_REFERENCE).toContain("payment reference");
  });
});
