import React, { useCallback, useEffect, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import { request } from "./api";
import { ForecastChart } from "./ForecastChart";
import { date, documentState, localToday, money } from "./format";
import type { Dashboard, Document } from "./types";
import { DocumentForm } from "./DocumentForm";
import { PlanningPanel } from "./PlanningPanel";
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
  const generation = useRef(0);

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

  const open =
    data?.documents.filter(
      (doc) => !doc.cancelled && doc.paidAmount < doc.amount,
    ) ?? [];
  const receivables = open
    .filter((doc) => doc.kind === "INVOICE")
    .reduce((sum, doc) => sum + doc.amount - doc.paidAmount, 0);
  const payables = open
    .filter((doc) => doc.kind === "EXPENSE")
    .reduce((sum, doc) => sum + doc.amount - doc.paidAmount, 0);
  const stale =
    data?.status.lastSync &&
    Date.now() - new Date(data.status.lastSync).getTime() > 15 * 60 * 1000;
  const demo = data?.status.mode === "demo";
  const planning =
    data?.planningBalance !== null && data?.planningBalance !== undefined;
  const hasOpeningBalance = !!data?.account || planning;

  return (
    <div className="layout">
      <aside className="sidebar">
        <a href="/" className="brand">
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
          <a href="#overview" className="nav-active">
            Overview <span>01</span>
          </a>
          <a href="#activity">
            Invoices & activity <span>02</span>
          </a>
          <a href="#assumptions">
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
      <main id="overview">
        <header className="topbar">
          <span>BUSINESS FINANCE / OVERVIEW</span>
          <span className={`mode-badge ${demo ? "demo" : ""}`}>
            {data ? (demo ? "DEMO DATA" : "NORDEA SANDBOX") : "CONNECTING"}
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
            {busy ? "Working..." : "Sync bank account"}{" "}
            <span aria-hidden="true">&rarr;</span>
          </button>
        </section>
        <div aria-live="polite">
          {loadError && (
            <div className="message error" role="alert">
              {loadError} {data && "Showing the last successful dashboard."}
              <button onClick={() => void reload()}>Retry</button>
            </div>
          )}
          {error && (
            <div className="message error" role="alert">
              {error}
            </div>
          )}
          {notice && <div className="message success">{notice}</div>}
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
        </div>
        {!data ? (
          <div className="panel empty">
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
                  : "Nordea Instant Reporting / Finnish EUR account"}
              </span>
              <span>
                {data.status.lastSync
                  ? `Last synced ${new Date(data.status.lastSync).toLocaleString("en-GB", { timeZone: "Europe/Helsinki" })} Helsinki`
                  : "Not synced yet"}
                {stale && " / STALE"}
                {data.status.pendingEvents > 0 &&
                  ` / ${data.status.pendingEvents} events awaiting reconciliation`}
              </span>
            </div>
            {!data.account && (
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
              busy={busy}
              onSave={(balance) =>
                void action(
                  () =>
                    request("/api/planning", "PUT", {
                      openingBalance: balance,
                    }),
                  balance === null
                    ? "Planning override cleared. Forecast uses the bank balance when available."
                    : "Planning scenario saved. The bank-reported balance is unchanged.",
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
            <section className="metrics" aria-label="Financial summary">
              <article className="metric">
                <span>Booked bank balance</span>
                <strong>
                  {data.account ? money(data.account.bookedBalance) : "--"}
                </strong>
                <small>
                  {data.account ? "Last bank snapshot / EUR" : "Sync required"}
                </small>
              </article>
              <article className="metric">
                <span>Outstanding invoices</span>
                <strong>{money(receivables)}</strong>
                <small>
                  {open.filter((doc) => doc.kind === "INVOICE").length} open
                  receivables / all dates
                </small>
              </article>
              <article className="metric">
                <span>Planned outgoings</span>
                <strong>{money(payables)}</strong>
                <small>
                  {open.filter((doc) => doc.kind === "EXPENSE").length} unpaid
                  expenses / all dates
                </small>
              </article>
              <article
                className={`metric ${data.forecast.minimumBalance < 0 && hasOpeningBalance ? "risk" : ""}`}
              >
                <span>Lowest projected balance</span>
                <strong>
                  {hasOpeningBalance
                    ? money(data.forecast.minimumBalance)
                    : "--"}
                </strong>
                <small>
                  {days}-day outlook /{" "}
                  {planning ? "PLANNING SCENARIO" : "bank-based"}
                </small>
              </article>
            </section>
            <section className="forecast-grid">
              <article className="panel chart-panel">
                <div className="panel-heading">
                  <div>
                    <p className="eyebrow">LOOK AHEAD</p>
                    <h2>Cash-flow forecast</h2>
                    <small>
                      {planning ? "Planning scenario" : "Bank-based forecast"} /
                      opening{" "}
                      {hasOpeningBalance
                        ? money(data.forecast.openingBalance)
                        : "not set"}
                    </small>
                  </div>
                  <label className="select-label">
                    Horizon
                    <select
                      disabled={busy}
                      value={days}
                      onChange={(event) => setDays(Number(event.target.value))}
                    >
                      <option value={30}>30 days</option>
                      <option value={60}>60 days</option>
                      <option value={90}>90 days</option>
                    </select>
                  </label>
                </div>
                <div className="scenario">
                  <span className="legend">
                    <i /> Projected closing balance
                  </span>
                  <label>
                    Customer payment delay{" "}
                    <select
                      disabled={busy}
                      aria-label="Customer payment delay"
                      value={delay}
                      onChange={(event) => setDelay(Number(event.target.value))}
                    >
                      <option value={0}>On time</option>
                      <option value={7}>7 days late</option>
                      <option value={14}>14 days late</option>
                      <option value={30}>30 days late</option>
                    </select>
                  </label>
                </div>
                {hasOpeningBalance ? (
                  <ForecastChart
                    series={data.forecast.series}
                    opening={data.forecast.openingBalance}
                  />
                ) : (
                  <div className="chart-placeholder">
                    Sync a bank snapshot or set a planning balance to see your
                    forecast.
                  </div>
                )}
                <div className="chart-footer">
                  <span>EUR / Daily closing balance</span>
                  <span>Scenario, not financial advice</span>
                </div>
              </article>
              <aside
                className={`insight ${data.forecast.firstShortfall && hasOpeningBalance ? "warning" : ""}`}
              >
                <p className="eyebrow">YOUR NEXT MOVE</p>
                <span className="insight-symbol" aria-hidden="true">
                  {data.forecast.firstShortfall && hasOpeningBalance
                    ? "!"
                    : "+"}
                </span>
                <h2>
                  {!hasOpeningBalance
                    ? "Start with the facts."
                    : data.forecast.firstShortfall
                      ? `A gap ahead on ${date(data.forecast.firstShortfall)}.`
                      : "Room to breathe."}
                </h2>
                <p>
                  {!hasOpeningBalance
                    ? "Sync an account or set a planning balance, then add your expected income and expenses."
                    : data.forecast.firstShortfall
                      ? `Your scenario falls below zero, reaching ${money(data.forecast.minimumBalance)}. Review upcoming receipts and non-critical spending before that date.`
                      : "No negative daily closing balance in this scenario. Late receipts or unrecorded expenses can still change the picture."}
                </p>
                <a href="#forecast-detail">
                  Review daily cash movements{" "}
                  <span aria-hidden="true">&rarr;</span>
                </a>
                <small>Rule-based insight / no AI-generated advice</small>
              </aside>
            </section>
            <section className="panel activity" id="activity">
              <div className="panel-heading">
                <div>
                  <p className="eyebrow">THE DETAILS BEHIND THE NUMBERS</p>
                  <h2>Invoices & activity</h2>
                </div>
                <button
                  className="primary"
                  disabled={busy}
                  onClick={() => {
                    setShowForm(!showForm);
                    setEditing(null);
                    setCancelling(null);
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
                  <p>
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
                      void action(async () => {
                        await request(
                          `/api/documents/${cancelling.id}/cancel`,
                          "POST",
                          { revision: cancelling.revision },
                        );
                        setCancelling(null);
                      }, "Document cancelled. Bank data is unchanged.")
                    }
                  >
                    Confirm cancellation
                  </button>
                  <button disabled={busy} onClick={() => setCancelling(null)}>
                    Keep document
                  </button>
                </div>
              )}
              <div className="activity-toolbar">
                <div className="tabs" role="tablist" aria-label="Activity type">
                  <button
                    id="documents-tab"
                    role="tab"
                    aria-selected={tab === "documents"}
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
                    aria-controls="activity-table"
                    className={tab === "transactions" ? "selected" : ""}
                    onClick={() => setTab("transactions")}
                  >
                    Bank transactions <span>{data.transactions.length}</span>
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
              <div
                className="table-scroll"
                id="activity-table"
                role="tabpanel"
                aria-labelledby={`${tab}-tab`}
              >
                {tab === "documents" ? (
                  <table>
                    <thead>
                      <tr>
                        <th>Customer / supplier</th>
                        <th>Reference</th>
                        <th>Due</th>
                        <th>Amount</th>
                        <th>Remaining</th>
                        <th>Status</th>
                        <th>Actions</th>
                      </tr>
                    </thead>
                    <tbody>
                      {data.documents.map((doc) => (
                        <tr key={doc.id}>
                          <td>
                            <strong>{doc.counterparty}</strong>
                            <small>
                              {doc.kind === "INVOICE"
                                ? "Customer invoice"
                                : "Planned expense"}
                            </small>
                          </td>
                          <td className="mono">{doc.reference}</td>
                          <td>
                            {date(doc.dueDate)}
                            {doc.expectedDate && (
                              <small>Expected {date(doc.expectedDate)}</small>
                            )}
                          </td>
                          <td>{money(doc.amount)}</td>
                          <td>
                            {doc.cancelled
                              ? "--"
                              : money(doc.amount - doc.paidAmount)}
                          </td>
                          <td>
                            <span
                              className={`status-pill ${doc.cancelled ? "cancelled" : doc.paidAmount >= doc.amount ? "settled" : doc.dueDate < localToday() ? "overdue" : ""}`}
                            >
                              {doc.cancelled
                                ? "Cancelled"
                                : documentState(doc.amount, doc.paidAmount)}
                              {!doc.cancelled &&
                                doc.paidAmount < doc.amount &&
                                doc.dueDate < localToday() &&
                                " / Overdue"}
                            </span>
                          </td>
                          <td>
                            {!doc.cancelled && (
                              <div className="row-actions">
                                <button
                                  disabled={busy}
                                  aria-label={`Edit ${doc.reference}`}
                                  onClick={() => {
                                    setEditing(doc);
                                    setShowForm(true);
                                    setCancelling(null);
                                    window.setTimeout(
                                      () =>
                                        document
                                          .getElementById("activity")
                                          ?.scrollIntoView({ block: "start" }),
                                      0,
                                    );
                                  }}
                                >
                                  Edit
                                </button>
                                <button
                                  disabled={busy || doc.paidAmount > 0}
                                  title={
                                    doc.paidAmount > 0
                                      ? "Matched documents cannot be cancelled"
                                      : "Cancel this document"
                                  }
                                  aria-label={`Cancel ${doc.reference}`}
                                  onClick={() => {
                                    setCancelling(doc);
                                    setShowForm(false);
                                    setEditing(null);
                                  }}
                                >
                                  Cancel
                                </button>
                              </div>
                            )}
                          </td>
                        </tr>
                      ))}
                      {data.documents.length === 0 && (
                        <tr>
                          <td colSpan={7} className="empty">
                            No documents yet. Add an invoice, import a CSV, or
                            load the demo.
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                ) : (
                  <table>
                    <thead>
                      <tr>
                        <th>Counterparty</th>
                        <th>Reference</th>
                        <th>Booked</th>
                        <th>Amount</th>
                        <th>Reconciliation</th>
                      </tr>
                    </thead>
                    <tbody>
                      {data.transactions.map((tx) => (
                        <tr key={`${tx.accountId}:${tx.externalId}`}>
                          <td>
                            <strong>{tx.counterparty}</strong>
                            <small className="mono">{tx.externalId}</small>
                          </td>
                          <td className="mono">{tx.reference || "--"}</td>
                          <td>{date(tx.bookingDate)}</td>
                          <td className={tx.amount > 0 ? "positive" : ""}>
                            {money(tx.amount)}
                          </td>
                          <td>
                            <span
                              className={`status-pill ${tx.documentId ? "settled" : ""}`}
                            >
                              {tx.documentId ? "Matched" : "Needs review"}
                            </span>
                          </td>
                        </tr>
                      ))}
                      {data.transactions.length === 0 && (
                        <tr>
                          <td colSpan={5} className="empty">
                            No bank transactions. Sync your account to import
                            them.
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                )}
              </div>
              <div className="table-note">
                Matches use payment reference, direction and remaining amount.
                Partial payments are supported; ambiguous or excessive payments
                stay unmatched.
              </div>
            </section>
            <details className="panel forecast-detail" id="forecast-detail">
              <summary>Daily forecast & cash movements</summary>
              <div className="table-scroll">
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
                  Single workspace and one Finnish EUR account. Sandbox data may
                  have historical dates. No payments, lending decisions or
                  production banking access.
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
