import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi, afterEach } from "vitest";
import { ActivityTable } from "./ActivityTable";
import { ForecastOverview } from "./ForecastOverview";
import { ProcessingPanel } from "./ProcessingPanel";
import { money } from "./format";
import type { Dashboard, Document, Transaction } from "./types";

const document: Document = {
  id: "invoice",
  kind: "INVOICE",
  counterparty: "Customer",
  reference: "INV",
  amount: 300,
  paidAmount: 100,
  dueDate: "2026-10-01",
  expectedDate: null,
  cancelled: false,
  revision: 0,
  recurringExpenseId: null,
  occurrenceMonth: null,
};
const payment: Transaction = {
  accountId: "EUR",
  externalId: "receipt",
  bookingDate: "2026-10-01",
  amount: 100,
  reference: "INV",
  counterparty: "Customer",
  documentId: "invoice",
  matchMode: "AUTO",
  reviewRevision: 0,
  explanation: "MATCHED_AUTOMATICALLY",
};
const dashboard: Dashboard = {
  status: {
    mode: "statement",
    lastSync: null,
    syncError: null,
    lastReconciled: null,
    pendingEvents: 0,
    eventError: null,
    statementDate: null,
    recurrenceError: null,
    pipeline: {
      unpublishedEvents: 0,
      awaitingReconciliation: 0,
      oldestPendingAt: null,
      failedPartitions: 0,
      consumerError: null,
    },
  },
  account: null,
  documents: [],
  planningBalance: null,
  transactions: [],
  recurringExpenses: [],
  forecast: {
    days: 30,
    incomeDelayDays: 0,
    openingBalance: 0,
    minimumBalance: 0,
    firstShortfall: null,
    series: [],
    assumptions: [],
    expectedReceipts: 0,
    plannedOutgoings: 0,
    closingBalance: 0,
    shortfallExplanation: null,
  },
};

function overview(data: Dashboard) {
  return renderToStaticMarkup(
    <ForecastOverview
      data={data}
      days={30}
      delay={0}
      busy={false}
      onDaysChange={() => {}}
      onDelayChange={() => {}}
    />,
  );
}

function activity({
  documents = [],
  transactions = [],
  tab = "documents",
  statement = false,
  busy = false,
}: {
  documents?: Document[];
  transactions?: Transaction[];
  tab?: "documents" | "transactions";
  statement?: boolean;
  busy?: boolean;
}) {
  return renderToStaticMarkup(
    <ActivityTable
      documents={documents}
      transactions={transactions}
      tab={tab}
      statement={statement}
      busy={busy}
      onEdit={() => {}}
      onCancel={() => {}}
      onReview={() => {}}
    />,
  );
}

describe("forecast overview", () => {
  it("does not present an uninitialised forecast as usable", () => {
    const html = overview(dashboard);
    expect(html).toContain("Import required");
    expect(html).toContain("Start with the facts.");
    expect(html).toContain("Import a statement");
    expect(html).not.toContain("<svg");
    expect(html).not.toContain("Room to breathe.");
  });

  it("treats a zero planning balance as a real override, without replacing the reported balance", () => {
    const withoutAccount = overview({ ...dashboard, planningBalance: 0 });
    expect(withoutAccount).toContain("PLANNING SCENARIO");
    expect(withoutAccount).not.toContain("Start with the facts.");
    const html = overview({
      ...dashboard,
      account: {
        id: "EUR",
        name: "Business",
        currency: "EUR",
        bookedBalance: 1234,
        observedAt: "2026-10-01T10:00:00Z",
      },
      planningBalance: 0,
    });
    expect(html).toContain(money(1234));
    expect(html).toContain("Statement closing balance");
    expect(html).toContain("PLANNING SCENARIO");
    expect(html).toContain("Planning scenario");
    expect(html).not.toContain("Start with the facts.");
  });

  it("keeps all-dates metrics separate from horizon totals and excludes paid/cancelled plans", () => {
    const html = overview({
      ...dashboard,
      documents: [
        document,
        { ...document, id: "paid", amount: 100 },
        { ...document, id: "cancelled", cancelled: true },
        {
          ...document,
          id: "later-expense",
          kind: "EXPENSE",
          amount: 900,
          paidAmount: 50,
          dueDate: "2027-10-01",
        },
      ],
    });
    expect(html).toContain(money(200));
    expect(html).toContain(money(850));
    expect(html).toContain("1 open receivables / all dates");
    expect(html).toContain("1 unpaid expenses / all dates");
  });

  it("distinguishes an already-negative opening from a later shortfall", () => {
    const html = overview({
      ...dashboard,
      planningBalance: -1,
      forecast: {
        ...dashboard.forecast,
        openingBalance: -1,
        minimumBalance: -1,
        firstShortfall: "2026-10-01",
        shortfallExplanation: {
          date: "2026-10-01",
          openingAlreadyNegative: true,
          balanceBeforeDay: -1,
          receipts: 0,
          outgoings: 0,
          closingBalance: -1,
          contributions: [],
        },
      },
    });
    expect(html).toContain("Opening balance below zero.");
    expect(html).toContain('class="metric risk"');
    expect(html).not.toContain("A gap ahead on");
  });
});

describe("activity tables", () => {
  afterEach(() => vi.useRealTimers());

  it("preserves overdue, partial, settled, cancelled and monthly occurrence labels", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-10-30T12:00:00Z"));
    const html = activity({
      documents: [
        {
          ...document,
          recurringExpenseId: "monthly",
          occurrenceMonth: "2026-10-01",
          expectedDate: "2026-11-02",
        },
        { ...document, id: "settled", amount: 100 },
        { ...document, id: "cancelled", cancelled: true },
      ],
    });

    expect(html).toContain('aria-labelledby="documents-tab"');
    expect(html).toContain("Partially paid / Overdue");
    expect(html).toContain("Monthly occurrence / 2026-10");
    expect(html).toContain("Expected 2 Nov");
    expect(html).toContain('class="status-pill settled">Settled');
    expect(html).toContain('class="status-pill cancelled">Cancelled');
    expect(html).toContain("Matched documents cannot be cancelled");
    expect(html.match(/aria-label="Edit INV"/g)).toHaveLength(2);
    expect(html.match(/<button disabled=""/g)).toHaveLength(2);
  });

  it("disables edits and cancellation while busy, including otherwise unpaid documents", () => {
    const html = activity({
      documents: [{ ...document, paidAmount: 0 }],
      busy: true,
    });
    expect(html.match(/<button disabled=""/g)).toHaveLength(2);
  });

  it("preserves payment match explanations and busy review controls", () => {
    const html = activity({
      tab: "transactions",
      busy: true,
      transactions: [
        payment,
        {
          ...payment,
          externalId: "unmatched",
          documentId: null,
          explanation: "MISSING_REFERENCE",
        },
      ],
    });
    expect(html).toContain('aria-labelledby="transactions-tab"');
    expect(html).toContain(">Matched</span>");
    expect(html).toContain(">Needs review</span>");
    expect(html).toContain('aria-label="Review payment receipt"');
    expect(html.match(/<button type="button" disabled=""/g)).toHaveLength(2);
  });

  it("keeps mode-specific empty transaction instructions and the empty document state", () => {
    expect(activity({})).toContain("No documents yet.");
    expect(activity({ tab: "transactions", statement: true })).toContain(
      "No statement transactions. Preview and import a CSV above.",
    );
    expect(activity({ tab: "transactions" })).toContain(
      "No bank transactions. Sync your account to import them.",
    );
  });
});

describe("processing diagnostics", () => {
  it("does not claim idle Kafka connectivity or a fresh bank snapshot", () => {
    const html = renderToStaticMarkup(
      <ProcessingPanel status={dashboard.status} />,
    );
    expect(html).toContain("Caught up");
    expect(html).toContain("does not prove");
    expect(html).toContain("None");
    expect(html).toContain("Not yet run");
  });

  it("distinguishes publication backlog from failed consumer partitions", () => {
    const status = {
      ...dashboard.status,
      pendingEvents: 3,
      pipeline: {
        ...dashboard.status.pipeline,
        unpublishedEvents: 2,
        awaitingReconciliation: 1,
        oldestPendingAt: "2026-10-30T10:00:00Z",
      },
    };
    const pending = renderToStaticMarkup(<ProcessingPanel status={status} />);
    expect(pending).toContain("Catching up");
    expect(pending).toContain("Waiting to publish");
    expect(pending).toContain("Published, awaiting reconciliation");
    expect(pending).toContain("Helsinki");
    const failed = renderToStaticMarkup(
      <ProcessingPanel
        status={{
          ...status,
          pendingEvents: 0,
          pipeline: { ...status.pipeline, failedPartitions: 1 },
        }}
      />,
    );
    expect(failed).toContain("Retrying a failure");
    expect(failed).toContain("no automatic discard");
  });
});
