import { date, documentState, localToday, money } from "./format";
import { matchExplanation } from "./reconciliation";
import type { Document, Transaction } from "./types";

export function ActivityTable({
  tab,
  documents,
  transactions,
  statement,
  busy,
  onEdit,
  onCancel,
  onReview,
}: {
  tab: "documents" | "transactions";
  documents: Document[];
  transactions: Transaction[];
  statement: boolean;
  busy: boolean;
  onEdit: (document: Document) => void;
  onCancel: (document: Document) => void;
  onReview: (transaction: Transaction) => void;
}) {
  return (
    <div
      className="table-scroll"
      id="activity-table"
      role="tabpanel"
      aria-labelledby={`${tab}-tab`}
    >
      {tab === "documents" ? (
        <DocumentsTable
          documents={documents}
          busy={busy}
          onEdit={onEdit}
          onCancel={onCancel}
        />
      ) : (
        <TransactionsTable
          transactions={transactions}
          statement={statement}
          busy={busy}
          onReview={onReview}
        />
      )}
    </div>
  );
}

function DocumentsTable({
  documents,
  busy,
  onEdit,
  onCancel,
}: {
  documents: Document[];
  busy: boolean;
  onEdit: (document: Document) => void;
  onCancel: (document: Document) => void;
}) {
  const today = localToday();
  return (
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
        {documents.map((doc) => {
          const overdue =
            !doc.cancelled &&
            doc.paidAmount < doc.amount &&
            doc.dueDate < today;
          return (
            <tr key={doc.id}>
              <td>
                <strong>{doc.counterparty}</strong>
                <small>
                  {doc.kind === "INVOICE"
                    ? "Customer invoice"
                    : "Planned expense"}
                </small>
                {doc.recurringExpenseId && (
                  <small>
                    Monthly occurrence / {doc.occurrenceMonth?.slice(0, 7)}
                  </small>
                )}
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
                {doc.cancelled ? "--" : money(doc.amount - doc.paidAmount)}
              </td>
              <td>
                <span
                  className={`status-pill ${doc.cancelled ? "cancelled" : doc.paidAmount >= doc.amount ? "settled" : overdue ? "overdue" : ""}`}
                >
                  {doc.cancelled
                    ? "Cancelled"
                    : documentState(doc.amount, doc.paidAmount)}
                  {overdue && " / Overdue"}
                </span>
              </td>
              <td>
                {!doc.cancelled && (
                  <div className="row-actions">
                    <button
                      disabled={busy}
                      aria-label={`Edit ${doc.reference}`}
                      onClick={() => onEdit(doc)}
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
                      onClick={() => onCancel(doc)}
                    >
                      Cancel
                    </button>
                  </div>
                )}
              </td>
            </tr>
          );
        })}
        {documents.length === 0 && (
          <tr>
            <td colSpan={7} className="empty">
              No documents yet. Add an invoice, import a CSV, or load the demo.
            </td>
          </tr>
        )}
      </tbody>
    </table>
  );
}

function TransactionsTable({
  transactions,
  statement,
  busy,
  onReview,
}: {
  transactions: Transaction[];
  statement: boolean;
  busy: boolean;
  onReview: (transaction: Transaction) => void;
}) {
  return (
    <table>
      <thead>
        <tr>
          <th>Counterparty</th>
          <th>Reference</th>
          <th>Booked</th>
          <th>Amount</th>
          <th>Reconciliation</th>
          <th>Review details</th>
        </tr>
      </thead>
      <tbody>
        {transactions.map((tx) => (
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
              <span className={`status-pill ${tx.documentId ? "settled" : ""}`}>
                {tx.documentId ? "Matched" : "Needs review"}
              </span>
            </td>
            <td>
              <small>{matchExplanation[tx.explanation]}</small>
              <button
                type="button"
                disabled={busy}
                aria-label={`Review payment ${tx.externalId}`}
                onClick={() => onReview(tx)}
              >
                Review
              </button>
            </td>
          </tr>
        ))}
        {transactions.length === 0 && (
          <tr>
            <td colSpan={6} className="empty">
              {statement
                ? "No statement transactions. Preview and import a CSV above."
                : "No bank transactions. Sync your account to import them."}
            </td>
          </tr>
        )}
      </tbody>
    </table>
  );
}
