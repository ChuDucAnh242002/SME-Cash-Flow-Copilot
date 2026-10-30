import { ForecastChart } from "./ForecastChart";
import { date, money } from "./format";
import type { Dashboard } from "./types";

export function ForecastOverview({
  data,
  days,
  delay,
  busy,
  onDaysChange,
  onDelayChange,
}: {
  data: Dashboard;
  days: number;
  delay: number;
  busy: boolean;
  onDaysChange: (days: number) => void;
  onDelayChange: (delay: number) => void;
}) {
  const open = data.documents.filter(
    (doc) => !doc.cancelled && doc.paidAmount < doc.amount,
  );
  const invoices = open.filter((doc) => doc.kind === "INVOICE");
  const expenses = open.filter((doc) => doc.kind === "EXPENSE");
  const receivables = invoices.reduce(
    (sum, doc) => sum + doc.amount - doc.paidAmount,
    0,
  );
  const payables = expenses.reduce(
    (sum, doc) => sum + doc.amount - doc.paidAmount,
    0,
  );
  const statement = data.status.mode === "statement";
  const planning = data.planningBalance !== null;
  const hasOpeningBalance = !!data.account || planning;
  const updating =
    data.forecast.days !== days || data.forecast.incomeDelayDays !== delay;

  return (
    <>
      {updating && (
        <p className="message info" role="status">
          Updating scenario... Previous forecast is shown until refresh
          completes.
        </p>
      )}
      <section
        className="metrics"
        aria-label="Financial summary"
        aria-busy={updating}
      >
        <article className="metric">
          <span>
            {statement ? "Statement closing balance" : "Booked bank balance"}
          </span>
          <strong>
            {data.account ? money(data.account.bookedBalance) : "--"}
          </strong>
          <small>
            {data.account
              ? statement
                ? "Uploaded / not bank-verified / EUR"
                : "Last bank snapshot / EUR"
              : statement
                ? "Import required"
                : "Sync required"}
          </small>
        </article>
        <article className="metric">
          <span>Outstanding invoices</span>
          <strong>{money(receivables)}</strong>
          <small>{invoices.length} open receivables / all dates</small>
        </article>
        <article className="metric">
          <span>Planned outgoings</span>
          <strong>{money(payables)}</strong>
          <small>{expenses.length} unpaid expenses / all dates</small>
        </article>
        <article
          className={`metric ${data.forecast.minimumBalance < 0 && hasOpeningBalance ? "risk" : ""}`}
        >
          <span>Lowest projected balance</span>
          <strong>
            {hasOpeningBalance ? money(data.forecast.minimumBalance) : "--"}
          </strong>
          <small>
            {data.forecast.days}-day outlook /{" "}
            {planning
              ? "PLANNING SCENARIO"
              : statement
                ? "statement-based"
                : "bank-based"}
          </small>
        </article>
      </section>
      <section className="forecast-grid" aria-busy={updating}>
        <article className="panel chart-panel">
          <div className="panel-heading">
            <div>
              <p className="eyebrow">LOOK AHEAD</p>
              <h2>Cash-flow forecast</h2>
              <small>
                {planning
                  ? "Planning scenario"
                  : statement
                    ? "Statement-based forecast"
                    : "Bank-based forecast"}{" "}
                / opening{" "}
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
                onChange={(event) => onDaysChange(Number(event.target.value))}
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
                onChange={(event) => onDelayChange(Number(event.target.value))}
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
              {statement ? "Import a statement" : "Sync a bank snapshot"} or set
              a planning balance to see your forecast.
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
            {data.forecast.firstShortfall && hasOpeningBalance ? "!" : "+"}
          </span>
          <h2>
            {!hasOpeningBalance
              ? "Start with the facts."
              : data.forecast.shortfallExplanation?.openingAlreadyNegative
                ? "Opening balance below zero."
                : data.forecast.firstShortfall
                  ? `A gap ahead on ${date(data.forecast.firstShortfall)}.`
                  : "Room to breathe."}
          </h2>
          <p>
            {!hasOpeningBalance
              ? `${statement ? "Import a statement" : "Sync an account"} or set a planning balance, then add your expected income and expenses.`
              : data.forecast.firstShortfall
                ? `Your scenario falls below zero, reaching ${money(data.forecast.minimumBalance)}. Review upcoming receipts and non-critical spending before that date.`
                : "No negative daily closing balance in this scenario. Late receipts or unrecorded expenses can still change the picture."}
          </p>
          <a href="#forecast-detail">
            Review daily cash movements <span aria-hidden="true">&rarr;</span>
          </a>
          <small>Rule-based insight / no AI-generated advice</small>
        </aside>
      </section>
    </>
  );
}
