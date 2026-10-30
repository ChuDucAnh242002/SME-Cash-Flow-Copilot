import { date, money } from "./format";
import type { Dashboard, Document } from "./types";

export function ForecastExplanation({
  forecast,
  documents,
  hasOpeningBalance,
  pendingEvents,
  busy,
  onEdit,
}: {
  forecast: Dashboard["forecast"];
  documents: Document[];
  hasOpeningBalance: boolean;
  pendingEvents: number;
  busy: boolean;
  onEdit: (document: Document) => void;
}) {
  const shortfall = forecast.shortfallExplanation;
  return (
    <section
      className="panel forecast-explanation"
      aria-label="Forecast explanation"
    >
      <p className="eyebrow">EXPLAIN THE NUMBERS</p>
      <h2>{forecast.days}-day cash breakdown</h2>
      {!hasOpeningBalance ? (
        <p>
          Set an opening balance or import account data before using this
          forecast.
        </p>
      ) : (
        <>
          <dl className="cash-breakdown">
            <div>
              <dt>Opening balance</dt>
              <dd>{money(forecast.openingBalance)}</dd>
            </div>
            <div>
              <dt>Expected receipts</dt>
              <dd>+ {money(forecast.expectedReceipts)}</dd>
            </div>
            <div>
              <dt>Planned expenses</dt>
              <dd>- {money(forecast.plannedOutgoings)}</dd>
            </div>
            <div>
              <dt>Projected closing balance</dt>
              <dd>{money(forecast.closingBalance)}</dd>
            </div>
          </dl>
          <p className="form-note">
            Only unpaid active documents scheduled within this horizon. Account
            transactions are already included in the opening balance, not added
            again. All-dates invoice/outgoing cards may differ from these
            horizon totals.
          </p>
          {pendingEvents > 0 && (
            <p className="message info">
              Payment matching is pending. This breakdown may change after
              reconciliation.
            </p>
          )}
          {shortfall ? (
            <div className="shortfall-explanation">
              <h3>
                {shortfall.openingAlreadyNegative
                  ? "The opening balance is already negative"
                  : `Why cash first falls below zero on ${date(shortfall.date)}`}
              </h3>
              <p>
                {shortfall.openingAlreadyNegative
                  ? "The shortage exists before any forecast movement. Today's payments may recover the closing balance; expenses below did not cause the negative opening."
                  : "This is a daily closing calculation, not intraday payment order. These figures explain the first shortage, not necessarily the lowest point."}
              </p>
              <p>
                {shortfall.openingAlreadyNegative
                  ? "Opening"
                  : "Before this day"}{" "}
                {money(shortfall.balanceBeforeDay)}
                {" + receipts "}
                {money(shortfall.receipts)}
                {" - expenses "}
                {money(shortfall.outgoings)}
                {" = closing "}
                <strong>{money(shortfall.closingBalance)}</strong>.
              </p>
              <details>
                <summary>
                  {shortfall.openingAlreadyNegative
                    ? "Movements on the opening day"
                    : "Documents through the first shortage"}{" "}
                  ({shortfall.contributions.length})
                </summary>
                {shortfall.contributions.length === 0 ? (
                  <p>No document movements on or before this day.</p>
                ) : (
                  <div className="table-scroll">
                    <table>
                      <thead>
                        <tr>
                          <th>Forecast date</th>
                          <th>Document</th>
                          <th>Movement</th>
                          <th>Action</th>
                        </tr>
                      </thead>
                      <tbody>
                        {shortfall.contributions.map((item) => {
                          const document = documents.find(
                            (value) => value.id === item.documentId,
                          );
                          return (
                            <tr key={item.documentId}>
                              <td>
                                {date(item.date)}
                                <small>
                                  {item.date === shortfall.date
                                    ? "First-shortage day"
                                    : "Earlier movement"}
                                </small>
                              </td>
                              <td>{item.label}</td>
                              <td>{money(item.amount)}</td>
                              <td>
                                {document && !document.cancelled && (
                                  <button
                                    type="button"
                                    disabled={busy}
                                    aria-label={`Adjust forecast ${document.reference}`}
                                    onClick={() => onEdit(document)}
                                  >
                                    Adjust expected date
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
              </details>
              <p className="form-note">
                Expected-date changes are saved workspace-wide and do not change
                contractual due dates. A later date is an assumption, not
                permission to delay a payment.
              </p>
            </div>
          ) : (
            <p>
              No negative opening or daily closing balance in this scenario.
            </p>
          )}
        </>
      )}
    </section>
  );
}
