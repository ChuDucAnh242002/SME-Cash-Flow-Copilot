import { useState } from "react";
import { request } from "./api";
import { date, localToday, money } from "./format";
import type { Document, RecurringExpense } from "./types";

export function RecurringExpensesPanel({
  rules,
  documents,
  busy,
  onAction,
}: {
  rules: RecurringExpense[];
  documents: Document[];
  busy: boolean;
  onAction: (run: () => Promise<unknown>, success: string) => Promise<void>;
}) {
  const [showForm, setShowForm] = useState(false);
  const [requestId, setRequestId] = useState(() => crypto.randomUUID());
  const [stopping, setStopping] = useState<RecurringExpense | null>(null);
  const stopStale =
    stopping &&
    rules.find((rule) => rule.id === stopping.id)?.revision !==
      stopping.revision;

  return (
    <details className="panel recurring-panel" id="recurring-expenses">
      <summary>Monthly recurring expenses ({rules.length} rules)</summary>
      <div className="recurring-content">
        <p>
          Plan rent, salaries or subscriptions. Monthly rules create ordinary
          expense documents through the next 90 days. The original day is
          preserved: January 31 becomes February's last day, then March 31.
        </p>
        <p className="form-note">
          New occurrences are generated every minute while the app runs; missed
          months catch up after restart. Each has a unique reference. If your
          bank repeats a reference, link the payment manually. Check for
          existing expenses before creating a rule; the app cannot detect
          duplicate obligations with different references.
        </p>
        <button
          type="button"
          disabled={busy}
          onClick={() => setShowForm(!showForm)}
        >
          {showForm ? "Close recurring form" : "Add monthly expense"}
        </button>
        {showForm && (
          <form
            className="recurring-form"
            onChange={() => setRequestId(crypto.randomUUID())}
            onSubmit={(event) => {
              event.preventDefault();
              const fields = new FormData(event.currentTarget);
              const input = {
                requestId,
                counterparty: String(fields.get("counterparty")),
                amount: String(fields.get("amount")),
                firstDueDate: String(fields.get("firstDueDate")),
                endDate: String(fields.get("endDate")) || null,
              };
              void onAction(async () => {
                await request("/api/recurring-expenses", "POST", input);
                setShowForm(false);
                setRequestId(crypto.randomUUID());
              }, "Monthly rule saved. Generated expenses use the existing forecast and payment matching.");
            }}
          >
            <label>
              Recurring supplier or description
              <input
                name="counterparty"
                required
                maxLength={160}
                disabled={busy}
                placeholder="Rent / Landlord Oy"
              />
            </label>
            <label>
              Monthly amount / EUR
              <input
                name="amount"
                type="number"
                step=".01"
                min=".01"
                max="999999999.99"
                required
                disabled={busy}
              />
            </label>
            <label>
              First monthly due date
              <input
                name="firstDueDate"
                type="date"
                min={localToday()}
                defaultValue={localToday()}
                required
                disabled={busy}
              />
            </label>
            <label>
              Last monthly due date (optional)
              <input
                name="endDate"
                type="date"
                min={localToday()}
                disabled={busy}
              />
            </label>
            <p className="form-note">
              The end date is inclusive. Rule details cannot be edited in this
              version. Edit individual occurrences in Documents. Stopping a rule
              keeps all existing expenses, including future ones; cancel
              unwanted unpaid occurrences individually.
            </p>
            <button type="submit" className="primary" disabled={busy}>
              Save monthly rule
            </button>
          </form>
        )}
        {stopping && (
          <div
            className="cancel-confirmation"
            role="region"
            aria-label="Confirm recurring rule stop"
          >
            <p>
              Stop generating new expenses for{" "}
              <strong>{stopping.counterparty}</strong>? All existing documents,
              amounts and payment matches remain unchanged. This cannot be
              undone. Cancel unwanted unpaid occurrences individually.
            </p>
            {stopStale && (
              <p role="alert">
                This rule changed. Close this confirmation and refresh.
              </p>
            )}
            <button
              type="button"
              disabled={busy || !!stopStale}
              onClick={() =>
                void onAction(async () => {
                  await request(
                    `/api/recurring-expenses/${stopping.id}/stop`,
                    "POST",
                    { revision: stopping.revision },
                  );
                  setStopping(null);
                }, "Monthly rule stopped. Existing expenses and matches are unchanged.")
              }
            >
              Confirm stop rule
            </button>
            <button
              type="button"
              disabled={busy}
              onClick={() => setStopping(null)}
            >
              Keep rule
            </button>
          </div>
        )}
        {rules.length === 0 ? (
          <p>No monthly rules yet.</p>
        ) : (
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>Expense</th>
                  <th>Monthly amount</th>
                  <th>Dates</th>
                  <th>Status</th>
                  <th>Occurrences</th>
                  <th>Action</th>
                </tr>
              </thead>
              <tbody>
                {rules.map((rule) => {
                  const occurrences = documents.filter(
                    (document) => document.recurringExpenseId === rule.id,
                  );
                  const ended =
                    !!rule.endDate && rule.nextDueDate > rule.endDate;
                  return (
                    <tr key={rule.id}>
                      <td>
                        <strong>{rule.counterparty}</strong>
                        <small>
                          Monthly / day {Number(rule.firstDueDate.slice(8))}
                        </small>
                      </td>
                      <td>{money(rule.amount)}</td>
                      <td>
                        {date(rule.firstDueDate)}{" "}
                        {rule.firstDueDate.slice(0, 4)}
                        <small>
                          {rule.endDate
                            ? `Through ${date(rule.endDate)} ${rule.endDate.slice(0, 4)}`
                            : "No end date"}
                        </small>
                      </td>
                      <td>
                        {rule.stopped
                          ? "Stopped"
                          : ended
                            ? "Fully generated"
                            : "Active"}
                      </td>
                      <td>
                        {occurrences.length} generated
                        <small>
                          {
                            occurrences.filter((document) => document.cancelled)
                              .length
                          }{" "}
                          cancelled / retained
                        </small>
                      </td>
                      <td>
                        {!rule.stopped && !ended && (
                          <button
                            type="button"
                            disabled={busy}
                            aria-label={`Stop monthly ${rule.counterparty}`}
                            onClick={() => setStopping(rule)}
                          >
                            Stop rule
                          </button>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </details>
  );
}
