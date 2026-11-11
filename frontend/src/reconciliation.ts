import type { Document, Transaction } from "./types";

export const matchExplanation: Record<Transaction["explanation"], string> = {
  MATCHED_AUTOMATICALLY: "Matched by payment reference",
  MATCHED_MANUALLY: "Manually linked / protected from automatic matching",
  EXCLUDED_BY_USER: "Held unmatched by your decision",
  MISSING_REFERENCE: "Missing payment reference",
  UNKNOWN_REFERENCE: "No document has this payment reference",
  CANCELLED_DOCUMENT: "The referenced document is cancelled",
  DIRECTION_MISMATCH: "Payment direction does not match the document",
  EXCESS_AMOUNT: "Payment exceeds the document's unpaid amount",
  ZERO_AMOUNT: "Zero-value payments cannot be linked",
  RECONCILIATION_PENDING: "Awaiting reconciliation",
};

export function eligibleDocuments(
  transaction: Transaction,
  documents: Document[],
): Document[] {
  return documents.filter(
    (document) =>
      !document.cancelled &&
      transaction.amount !== 0 &&
      (document.kind === "INVOICE") === transaction.amount > 0 &&
      Math.round((document.amount - document.paidAmount) * 100) >=
        Math.round(Math.abs(transaction.amount) * 100),
  );
}
