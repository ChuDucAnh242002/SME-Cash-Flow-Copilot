import React, { useCallback, useEffect, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import { request } from "./api";
import { date, money } from "./format";
import type { Dashboard, Document, Transaction } from "./types";
import { DocumentForm } from "./DocumentForm";
import { PlanningPanel } from "./PlanningPanel";
import { StatementPanel } from "./StatementPanel";
import { PaymentReviewPanel } from "./PaymentReviewPanel";
import { RecurringExpensesPanel } from "./RecurringExpensesPanel";
import { ForecastExplanation } from "./ForecastExplanation";
import { ActivityTable } from "./ActivityTable";
import { ForecastOverview } from "./ForecastOverview";
import "./style.css";

function App() {
  const [data, setData] = useState<Dashboard | null>(null);
  const [days, setDays] = useState(30);
  const [delay, setDelay] = useState(0);
  const [tab, setTab] = useState<"documents" | "transactions">("documents");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [loadError, setLoadError] = useState("");
  const [showForm, setShowForm] = useState(false);
  const [editing, setEditing] = useState<Document | null>(null);
  const [cancelling, setCancelling] = useState<Document | null>(null);
  const [reviewing, setReviewing] = useState<Transaction | null>(null);
  const generation = useRef(0);
  const activityOrigin = useRef<HTMLElement | null>(null);
  const addDocumentButton = useRef<HTMLButtonElement>(null);
  const cancelHeading = useRef<HTMLParagraphElement>(null);
  const actionError = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (
      !showForm &&
      !cancelling &&
      !reviewing &&
      !busy &&
      activityOrigin.current
    ) {
      const origin = activityOrigin.current;
      activityOrigin.current = null;
      if (origin.isConnected && !origin.matches(":disabled")) origin.focus();
      else addDocumentButton.current?.focus();
    }
  }, [showForm, cancelling, reviewing, busy]);

  useEffect(() => {
    if (cancelling) cancelHeading.current?.focus();
  }, [cancelling]);

  useEffect(() => {
    if (error) actionError.current?.focus();
  }, [error]);

  const reload = useCallback(async () => {
    const current = ++generation.current;
    try {
      const next = await request<Dashboard>(
        `/api/dashboard?days=${days}&incomeDelayDays=${delay}`,
      );
      if (current === generation.current) {
        setData(next);
        setLoadError("");
      }
    } catch (failure) {
      if (current === generation.current)
        setLoadError(
          failure instanceof Error
            ? failure.message
            : "Dashboard refresh failed.",
        );
    }
  }, [days, delay]);

  useEffect(() => {
    void reload();
    const timer = window.setInterval(() => {
      void reload();
    }, 5000);
    return () => {
      window.clearInterval(timer);
      generation.current++;
    };
  }, [reload]);

  async function action(run: () => Promise<unknown>, success: string) {
    setBusy(true);
    setError("");
    setNotice("");
    try {
      await run();
      setNotice(success);
      await reload();
    } catch (failure) {
      setError(
        failure instanceof Error ? failure.message : "The operation failed.",
      );
    } finally {
      setBusy(false);
    }
  }

  const stale =
    data?.status.lastSync &&
    Date.now() - new Date(data.status.lastSync).getTime() > 15 * 60 * 1000;
  const demo = data?.status.mode === "demo";
  const statement = data?.status.mode === "statement";
  const planning =
    data?.planningBalance !== null && data?.planningBalance !== undefined;
  const hasOpeningBalance = !!data?.account || planning;

  function rememberActivityOrigin() {
    const active = window.document.activeElement;
    activityOrigin.current = active instanceof HTMLElement ? active : null;
  }

  function editDocument(document: Document) {
    rememberActivityOrigin();
    setEditing(document);
    setShowForm(true);
    setCancelling(null);
    setReviewing(null);
    setTab("documents");
  }

  return (
    <div className="layout">
      <a className="skip-link" href="#overview">
        Skip to main content
      </a>
      <aside className="sidebar">
        <a href="/" className="brand" aria-label="Cashflow home">
          <span className="brand-mark">cf</span> cashflow
          <span className="brand-dot">.</span>
        </a>
        <span className="workspace-label">YOUR WORKSPACE</span>
        <div className="workspace">
          <span className="workspace-avatar">FI</span>
          <div>
            <strong>{data?.account?.name ?? "Your business"}</strong>
            <small>Finland / EUR</small>
          </div>
        </div>
        <nav aria-label="Main navigation">
          <a href="#overview" className="nav-active" aria-label="Overview">
            Overview <span>01</span>
          </a>
          <a href="#activity" aria-label="Invoices and activity">
            Invoices & activity <span>02</span>
          </a>
          <a href="#assumptions" aria-label="How it works">
            How it works <span>03</span>
          </a>
        </nav>
        <div className="sidebar-note">
          <span className="live-dot" /> READ-ONLY MVP
          <p>
            No money moves.
            <br />
            You stay in control.
          </p>
          <small>Spring Boot / Kafka / Kubernetes</small>
        </div>
      </aside>
      <main id="overview" tabIndex={-1}>
        <header className="topbar">
          <span>BUSINESS FINANCE / OVERVIEW</span>
          <span className={`mode-badge ${demo ? "demo" : ""}`}>
            {data
              ? demo
                ? "DEMO DATA"
                : statement
                  ? "UPLOADED STATEMENTS"
                  : "NORDEA SANDBOX"
              : "CONNECTING"}
          </span>
        </header>
        <section className="page-heading">
          <div>
            <p className="eyebrow">A CLEARER VIEW OF WHAT COMES NEXT</p>
            <h1>Your cash, in perspective.</h1>
            <p className="subtitle">
              Less spreadsheet guesswork. More room to plan.
            </p>
          </div>
          {!statement && (
            <button
              className="primary"
              disabled={busy || !data}
              onClick={() =>
                void action(
                  () => request("/api/bank/sync", "POST"),
                  "Bank sync complete. Reconciliation is processed through Kafka.",
                )
              }
            >
              Sync bank account <span aria-hidden="true">&rarr;</span>
            </button>
          )}
        </section>
        <div>
          <div role="status" aria-live="polite" aria-atomic="true">
            {busy ? (
              <div className="message info">Working... Please wait.</div>
            ) : (
              notice && <div className="message success">{notice}</div>
            )}
          </div>
          {loadError && (
            <div className="message error" role="alert">
              {loadError} {data && "Showing the last successful dashboard."}
              <button onClick={() => void reload()}>Retry</button>
            </div>
          )}
          {error && (
            <div
              className="message error"
              role="alert"
              ref={actionError}
              tabIndex={-1}
            >
              {error}
            </div>
          )}
          {data?.status.syncError && (
            <div className="message error" role="alert">
              Last bank sync failed: {data.status.syncError}
            </div>
          )}
          {data?.status.eventError && (
            <div className="message error" role="alert">
              {data.status.eventError}
            </div>
          )}
          {data?.status.recurrenceError && (
            <div className="message error" role="alert">
              {data.status.recurrenceError}
            </div>
          )}
        </div>
        {!data ? (
          <div className="panel empty" role="status">
            {loadError
              ? "The dashboard is unavailable."
              : "Loading your workspace..."}
          </div>
        ) : (
          <>
            <div className="source-strip">
              <span>
                <span className="live-dot" />{" "}
                {demo
                  ? "Synthetic scenario. Not real bank data."
                  : statement
                    ? "User-uploaded EUR statements. Not bank-verified."
                    : "Nordea Instant Reporting / Finnish EUR account"}
              </span>
              <span>
                {data.status.lastSync
                  ? `Last ${statement ? "imported" : "synced"} ${new Date(data.status.lastSync).toLocaleString("en-GB", { timeZone: "Europe/Helsinki" })} Helsinki`
                  : statement
                    ? "No statement imported"
                    : "Not synced yet"}
                {stale && !statement && " / STALE"}
                {data.status.pendingEvents > 0 &&
                  ` / ${data.status.pendingEvents} events awaiting reconciliation`}
              </span>
            </div>
            {statement && (
              <StatementPanel
                account={data.account}
                closingDate={data.status.statementDate}
                busy={busy}
                onBusyChange={setBusy}
                onImported={reload}
              />
            )}
            {statement && data.account && data.status.statementDate && (
              <div className="message info">
                Statement closing date: {date(data.status.statementDate)}. The
                forecast starts today using the uploaded balance unless a
                planning override is set. Activity since that date is unknown
                until you import a newer statement. Historical payments are not
                added again.
              </div>
            )}
            {!data.account && !statement && (
              <div className="message info">
                Start with <strong>Sync bank account</strong>.{" "}
                {demo
                  ? "Then load the demo scenario below, or enter your own invoices."
                  : "Configure your local sandbox credentials before syncing, or set a planning balance below."}
              </div>
            )}
            <PlanningPanel
              balance={data.planningBalance}
              hasBank={!!data.account}
              statement={statement}
              busy={busy}
              onSave={(balance) =>
                void action(
                  () =>
                    request("/api/planning", "PUT", {
                      openingBalance: balance,
                    }),
                  balance === null
                    ? `Planning override cleared. Forecast uses the ${statement ? "statement" : "bank"} balance when available.`
                    : `Planning scenario saved. The ${statement ? "uploaded statement" : "bank-reported"} balance is unchanged.`,
                )
              }
            />
            {demo && data.documents.length === 0 && (
              <div className="welcome-panel">
                <div>
                  <strong>
                    A small business. A familiar cash-flow squeeze.
                  </strong>
                  <p>
                    Load sample invoices, salaries and supplier bills to explore
                    a projected shortfall.
                  </p>
                </div>
                <button
                  disabled={busy}
                  onClick={() =>
                    void action(
                      () => request("/api/demo/seed", "POST"),
                      "Demo documents loaded. Sync the bank to reconcile the sample payments.",
                    )
                  }
                >
                  Load demo scenario
                </button>
              </div>
            )}
            <ForecastOverview
              data={data}
              days={days}
              delay={delay}
              busy={busy}
              onDaysChange={setDays}
              onDelayChange={setDelay}
            />
            <ForecastExplanation
              forecast={data.forecast}
              documents={data.documents}
              hasOpeningBalance={hasOpeningBalance}
              pendingEvents={data.status.pendingEvents}
              busy={busy}
              onEdit={editDocument}
            />
            <RecurringExpensesPanel
              rules={data.recurringExpenses}
              documents={data.documents}
              busy={busy}
              onAction={action}
            />
            <section className="panel activity" id="activity">
              <div className="panel-heading">
                <div>
                  <p className="eyebrow">THE DETAILS BEHIND THE NUMBERS</p>
                  <h2>Invoices & activity</h2>
                </div>
                <button
                  ref={addDocumentButton}
                  className="primary"
                  disabled={busy}
                  aria-expanded={showForm}
                  aria-controls={showForm ? "document-form" : undefined}
                  onClick={() => {
                    if (!showForm) rememberActivityOrigin();
                    setShowForm(!showForm);
                    setEditing(null);
                    setCancelling(null);
                    setReviewing(null);
                  }}
                >
                  {showForm ? "Close form" : "+ Add invoice or expense"}
                </button>
              </div>
              {showForm && (
                <DocumentForm
                  key={editing ? `${editing.id}-${editing.revision}` : "new"}
                  document={editing}
                  busy={busy}
                  onSave={(input) =>
                    void action(
                      async () => {
                        if (editing)
                          await request(`/api/documents/${editing.id}`, "PUT", {
                            document: input,
                            revision: editing.revision,
                          });
                        else await request("/api/documents", "POST", input);
                        setShowForm(false);
                        setEditing(null);
                      },
                      editing
                        ? "Document updated and reconciliation refreshed."
                        : "Document saved. Kafka reconciliation will update payment matches.",
                    )
                  }
                />
              )}
              {cancelling && (
                <div
                  className="cancel-confirmation"
                  role="region"
                  aria-label="Confirm document cancellation"
                >
                  <p ref={cancelHeading} tabIndex={-1}>
                    Cancel{" "}
                    <strong>
                      {cancelling.counterparty} / {cancelling.reference}
                    </strong>
                    ? It will stay in the list for review but no longer affect
                    the forecast. This cannot be undone.
                  </p>
                  <button
                    disabled={busy}
                    onClick={() =>
                      void action(
                        async () => {
                          await request(
                            `/api/documents/${cancelling.id}/cancel`,
                            "POST",
                            { revision: cancelling.revision },
                          );
                          setCancelling(null);
                        },
                        `Document cancelled. ${statement ? "Statement" : "Bank"} data is unchanged.`,
                      )
                    }
                  >
                    Confirm cancellation
                  </button>
                  <button disabled={busy} onClick={() => setCancelling(null)}>
                    Keep document
                  </button>
                </div>
              )}
              {reviewing && (
                <PaymentReviewPanel
                  key={JSON.stringify([
                    reviewing.accountId,
                    reviewing.externalId,
                  ])}
                  transaction={reviewing}
                  current={data.transactions.find(
                    (transaction) =>
                      transaction.accountId === reviewing.accountId &&
                      transaction.externalId === reviewing.externalId,
                  )}
                  documents={data.documents}
                  busy={busy}
                  onBusyChange={setBusy}
                  onClose={() => setReviewing(null)}
                  onSaved={async () => {
                    setReviewing(null);
                    setNotice(
                      "Payment correction saved. Review history recorded; bank and statement facts are unchanged.",
                    );
                    await reload();
                  }}
                />
              )}
              <div className="activity-toolbar">
                <div
                  className="tabs"
                  role="tablist"
                  aria-label="Activity type"
                  onKeyDown={(event) => {
                    if (
                      !["ArrowLeft", "ArrowRight", "Home", "End"].includes(
                        event.key,
                      )
                    )
                      return;
                    event.preventDefault();
                    const next =
                      event.key === "Home"
                        ? "documents"
                        : event.key === "End"
                          ? "transactions"
                          : tab === "documents"
                            ? "transactions"
                            : "documents";
                    setTab(next);
                    window.document.getElementById(`${next}-tab`)?.focus();
                  }}
                >
                  <button
                    id="documents-tab"
                    role="tab"
                    aria-selected={tab === "documents"}
                    tabIndex={tab === "documents" ? 0 : -1}
                    aria-controls="activity-table"
                    className={tab === "documents" ? "selected" : ""}
                    onClick={() => setTab("documents")}
                  >
                    Documents <span>{data.documents.length}</span>
                  </button>
                  <button
                    id="transactions-tab"
                    role="tab"
                    aria-selected={tab === "transactions"}
                    tabIndex={tab === "transactions" ? 0 : -1}
                    aria-controls="activity-table"
                    className={tab === "transactions" ? "selected" : ""}
                    onClick={() => setTab("transactions")}
                  >
                    {statement ? "Statement transactions" : "Bank transactions"}{" "}
                    <span>{data.transactions.length}</span>
                  </button>
                </div>
                <label className={`upload-button ${busy ? "disabled" : ""}`}>
                  Import CSV
                  <input
                    aria-label="Import documents CSV"
                    type="file"
                    accept=".csv,text/csv"
                    disabled={busy}
                    onChange={(event) => {
                      const file = event.target.files?.[0];
                      event.target.value = "";
                      if (!file) return;
                      const body = new FormData();
                      body.append("file", file);
                      void action(
                        () => request("/api/documents/import", "POST", body),
                        "CSV imported. All rows were saved atomically.",
                      );
                    }}
                  />
                </label>
              </div>
              <ActivityTable
                tab={tab}
                documents={data.documents}
                transactions={data.transactions}
                statement={statement}
                busy={busy}
                onEdit={editDocument}
                onCancel={(document) => {
                  rememberActivityOrigin();
                  setCancelling(document);
                  setShowForm(false);
                  setEditing(null);
                  setReviewing(null);
                }}
                onReview={(transaction) => {
                  rememberActivityOrigin();
                  setReviewing(transaction);
                  setShowForm(false);
                  setEditing(null);
                  setCancelling(null);
                }}
              />
              <div className="table-note">
                Matches use payment reference, direction and remaining amount.
                Partial payments are supported; ambiguous or excessive payments
                stay unmatched. Manual links are protected. Removed matches stay
                unmatched until you explicitly resume automatic matching.
              </div>
            </section>
            <details className="panel forecast-detail" id="forecast-detail">
              <summary>Daily forecast & cash movements</summary>
              <div
                className="table-scroll"
                role="region"
                aria-label="Daily forecast movements"
                tabIndex={0}
              >
                <table>
                  <thead>
                    <tr>
                      <th>Date</th>
                      <th>Expected movements</th>
                      <th>Closing balance</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.forecast.series.map((day) => (
                      <tr key={day.date}>
                        <td>{date(day.date)}</td>
                        <td>
                          {day.items.length
                            ? day.items.map((item) => (
                                <div key={item.documentId}>
                                  {item.label}: {money(item.amount)}
                                </div>
                              ))
                            : "No planned movement"}
                        </td>
                        <td className={day.balance < 0 ? "negative" : ""}>
                          {hasOpeningBalance
                            ? money(day.balance)
                            : "Opening balance required"}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </details>
            <details className="panel assumptions" id="assumptions">
              <summary>
                Transparent by design: assumptions & limitations
              </summary>
              <ul>
                {data.forecast.assumptions.map((item) => (
                  <li key={item}>{item}</li>
                ))}
                <li>
                  Invoices and planned expenses are entered manually or imported
                  from CSV. Forecasts are not machine-learning predictions.
                </li>
                <li>
                  CSV header:{" "}
                  <code>kind,counterparty,reference,amount,dueDate</code>. Use
                  INVOICE or EXPENSE, decimal EUR amounts, ISO dates, and a
                  unique reference per document.
                </li>
                <li>
                  {statement
                    ? "Single workspace and one uploaded EUR account. Statements are user-supplied, not verified with a bank. Closing balance includes historical transactions; they are not added again."
                    : "Single workspace and one Finnish EUR account. Sandbox data may have historical dates."}{" "}
                  No payments, lending decisions or production banking access.
                </li>
                <li>
                  Reconciliation is asynchronous. During a Kafka outage,
                  documents are durable but unmatched items can temporarily
                  double-count expected cash movements. Wait until pending
                  events reach zero.
                </li>
              </ul>
            </details>
            <footer className="page-footer">
              <span>SME Cash-Flow Copilot / v0.1</span>
              <span>
                Independent prototype. Not affiliated with or endorsed by
                Nordea.
              </span>
            </footer>
          </>
        )}
      </main>
    </div>
  );
}

createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
