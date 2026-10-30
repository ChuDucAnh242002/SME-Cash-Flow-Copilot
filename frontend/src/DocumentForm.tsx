import { localToday } from "./format";
import type { Document } from "./types";

export interface DocumentFields {
  kind: string;
  counterparty: string;
  reference: string;
  amount: string;
  dueDate: string;
  expectedDate: string | null;
}

export function DocumentForm({
  document,
  busy,
  onSave,
}: {
  document: Document | null;
  busy: boolean;
  onSave: (input: DocumentFields) => void;
}) {
  const matched = !!document && document.paidAmount > 0;
  return (
    <form
      className="document-form"
      aria-label={document ? "Edit document" : "New document"}
      onSubmit={(event) => {
        event.preventDefault();
        const fields = new FormData(event.currentTarget);
        onSave({
          kind:
            document && (matched || document.recurringExpenseId)
              ? document.kind
              : String(fields.get("kind")),
          counterparty: String(fields.get("counterparty")),
          reference: matched
            ? document.reference
            : String(fields.get("reference")),
          amount: String(fields.get("amount")),
          dueDate: String(fields.get("dueDate")),
          expectedDate: String(fields.get("expectedDate") ?? "") || null,
        });
      }}
    >
      <label>
        Type
        <select
          name="kind"
          defaultValue={document?.kind ?? "INVOICE"}
          disabled={busy || matched || !!document?.recurringExpenseId}
        >
          <option value="INVOICE">Customer invoice</option>
          <option value="EXPENSE">Planned expense</option>
        </select>
      </label>
      <label>
        Customer or supplier
        <input
          name="counterparty"
          required
          maxLength={160}
          placeholder="Company Oy"
          defaultValue={document?.counterparty}
          disabled={busy}
        />
      </label>
      <label>
        Payment reference
        <input
          name="reference"
          required
          maxLength={80}
          placeholder="Unique bank reference"
          defaultValue={document?.reference}
          disabled={busy || matched}
        />
      </label>
      <label>
        Amount / EUR
        <input
          name="amount"
          required
          type="number"
          min={matched ? document.paidAmount : ".01"}
          max="999999999.99"
          step=".01"
          placeholder="0.00"
          defaultValue={document?.amount}
          disabled={busy}
        />
      </label>
      <label>
        Due date
        <input
          name="dueDate"
          required
          type="date"
          defaultValue={document?.dueDate ?? localToday()}
          disabled={busy}
        />
      </label>
      <label>
        Expected payment date
        <input
          name="expectedDate"
          type="date"
          defaultValue={document?.expectedDate ?? ""}
          disabled={busy}
        />
        <small>
          Optional. Used in the forecast; leaves the due date unchanged.
        </small>
      </label>
      {matched && (
        <p className="form-note">
          Matched payments are preserved. Type and reference are locked; amount
          cannot be below the matched total.
        </p>
      )}
      <button type="submit" className="primary" disabled={busy}>
        {document ? "Save changes" : "Save document"}
      </button>
    </form>
  );
}
