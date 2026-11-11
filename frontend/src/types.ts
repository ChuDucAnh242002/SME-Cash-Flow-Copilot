export type Kind = "INVOICE" | "EXPENSE";
export interface Document {
  id: string;
  kind: Kind;
  counterparty: string;
  reference: string;
  amount: number;
  dueDate: string;
  paidAmount: number;
  expectedDate: string | null;
  cancelled: boolean;
  revision: number;
  recurringExpenseId: string | null;
  occurrenceMonth: string | null;
}
export interface RecurringExpense {
  id: string;
  counterparty: string;
  amount: number;
  firstDueDate: string;
  endDate: string | null;
  nextMonth: string;
  nextDueDate: string;
  stopped: boolean;
  revision: number;
}
export interface Transaction {
  accountId: string;
  externalId: string;
  bookingDate: string;
  amount: number;
  reference: string;
  counterparty: string;
  documentId: string | null;
  matchMode: "AUTO" | "MANUAL" | "EXCLUDED";
  reviewRevision: number;
  explanation:
    | "MATCHED_AUTOMATICALLY"
    | "MATCHED_MANUALLY"
    | "EXCLUDED_BY_USER"
    | "MISSING_REFERENCE"
    | "UNKNOWN_REFERENCE"
    | "CANCELLED_DOCUMENT"
    | "DIRECTION_MISMATCH"
    | "EXCESS_AMOUNT"
    | "ZERO_AMOUNT"
    | "RECONCILIATION_PENDING";
}
export type ReviewAction = "LINK" | "UNLINK" | "RESUME";
export interface PaymentReviewAudit {
  id: number;
  action: ReviewAction;
  previousMode: Transaction["matchMode"];
  previousDocumentId: string | null;
  previousDocumentLabel: string | null;
  nextMode: Transaction["matchMode"];
  nextDocumentId: string | null;
  nextDocumentLabel: string | null;
  reason: string;
  actor: string;
  occurredAt: string;
}
export interface ForecastDay {
  date: string;
  balance: number;
  items: { documentId: string; label: string; amount: number }[];
}
export interface Dashboard {
  status: {
    mode: "demo" | "nordea" | "statement";
    lastSync: string | null;
    syncError: string | null;
    lastReconciled: string | null;
    pendingEvents: number;
    eventError: string | null;
    statementDate: string | null;
    recurrenceError: string | null;
    pipeline: {
      unpublishedEvents: number;
      awaitingReconciliation: number;
      oldestPendingAt: string | null;
      failedPartitions: number;
      consumerError: string | null;
    };
  };
  account: {
    id: string;
    name: string;
    currency: string;
    bookedBalance: number;
    observedAt: string;
  } | null;
  documents: Document[];
  planningBalance: number | null;
  transactions: Transaction[];
  recurringExpenses: RecurringExpense[];
  forecast: {
    days: number;
    incomeDelayDays: number;
    openingBalance: number;
    minimumBalance: number;
    firstShortfall: string | null;
    series: ForecastDay[];
    assumptions: string[];
    expectedReceipts: number;
    plannedOutgoings: number;
    closingBalance: number;
    shortfallExplanation: {
      date: string;
      openingAlreadyNegative: boolean;
      balanceBeforeDay: number;
      receipts: number;
      outgoings: number;
      closingBalance: number;
      contributions: {
        date: string;
        documentId: string;
        label: string;
        amount: number;
      }[];
    } | null;
  };
}

export interface StatementPreview {
  revision: number;
  statement: {
    accountId: string;
    accountName: string;
    closingBalance: number;
    closingDate: string;
  };
  newCount: number;
  duplicateCount: number;
  rows: {
    transaction: {
      transactionId: string;
      bookingDate: string;
      amount: number;
      reference: string;
      counterparty: string;
    };
    duplicate: boolean;
  }[];
}
