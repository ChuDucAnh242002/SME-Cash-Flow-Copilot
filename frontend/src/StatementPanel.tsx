import { useState } from "react";
import { request } from "./api";
import { date, localToday, money } from "./format";
import type { Dashboard, StatementPreview } from "./types";

export function StatementPanel({
  account,
  closingDate,
  busy,
  onBusyChange,
  onImported,
}: {
  account: Dashboard["account"];
  closingDate: string | null;
  busy: boolean;
  onBusyChange: (busy: boolean) => void;
  onImported: () => Promise<void>;
}) {
  const [preview, setPreview] = useState<{
    result: StatementPreview;
    upload: FormData;
  } | null>(null);
  const [acknowledged, setAcknowledged] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  function invalidate() {
    setPreview(null);
    setAcknowledged(false);
    setError("");
    setNotice("");
  }

  async function inspect(upload: FormData) {
    invalidate();
    onBusyChange(true);
    try {
      const result = await request<StatementPreview>(
        "/api/statements/preview",
        "POST",
        upload,
      );
      setPreview({ result, upload });
    } catch (failure) {
      setError(
        failure instanceof Error
          ? failure.message
          : "Statement preview failed.",
      );
    } finally {
      onBusyChange(false);
    }
  }

  async function confirm() {
    if (!preview || !acknowledged) return;
    onBusyChange(true);
    setError("");
    try {
      const upload = new FormData();
      preview.upload.forEach((value, key) => upload.append(key, value));
      upload.set("revision", String(preview.result.revision));
      const result = await request<{ imported: number; observed: number }>(
        "/api/statements/import",
        "POST",
        upload,
      );
      setPreview(null);
      setAcknowledged(false);
      setNotice(
        `Imported ${result.imported} new transactions; skipped ${result.observed - result.imported} duplicates. Reconciliation is processed through Kafka. The closing balance already includes these payments.`,
      );
      await onImported();
    } catch (failure) {
      setError(
        failure instanceof Error ? failure.message : "Statement import failed.",
      );
      setPreview(null);
      setAcknowledged(false);
    } finally {
      onBusyChange(false);
    }
  }

  return (
    <section className="panel statement-panel" aria-label="Statement import">
      <p className="eyebrow">UPLOADED STATEMENTS / NOT BANK-VERIFIED</p>
      <h2>Import a EUR bank statement</h2>
      <p>
        This isolated workspace never connects to Nordea. Use stable transaction
        IDs from your statement and enter its closing balance, not a balance
        before the listed payments.
      </p>
      <p className="statement-format">
        UTF-8 CSV, up to 500 transactions / 256 KB. Exact header:{" "}
        <code>transactionId,bookingDate,amount,reference,counterparty</code>.
        Positive receipts, negative expenses, decimal EUR amounts and ISO dates.
        No automatic bank-format detection.
      </p>
      <form
        onSubmit={(event) => {
          event.preventDefault();
          void inspect(new FormData(event.currentTarget));
        }}
      >
        <fieldset disabled={busy} onChange={invalidate}>
          <legend>Statement details</legend>
          <label>
            Statement account ID
            <input
              name="accountId"
              required
              pattern="[A-Za-z0-9._:\-]{1,128}"
              maxLength={128}
              defaultValue={account?.id ?? ""}
              readOnly={!!account}
              placeholder="FI-company-EUR"
            />
          </label>
          <label>
            Statement account name
            <input
              name="accountName"
              required
              maxLength={160}
              defaultValue={account?.name ?? ""}
              placeholder="Company Oy"
            />
          </label>
          <label>
            Statement closing balance / EUR
            <input
              name="closingBalance"
              type="number"
              required
              step=".01"
              min="-999999999.99"
              max="999999999.99"
              defaultValue={account?.bookedBalance ?? ""}
            />
          </label>
          <label>
            Statement closing date
            <input
              name="closingDate"
              type="date"
              required
              max={localToday()}
              defaultValue={closingDate ?? localToday()}
            />
          </label>
          <label>
            Statement CSV
            <input name="file" type="file" accept=".csv,text/csv" required />
          </label>
        </fieldset>
        <button className="primary" type="submit" disabled={busy}>
          Preview statement
        </button>
      </form>
      <div aria-live="polite">
        {error && (
          <div className="message error" role="alert">
            {error}
          </div>
        )}
        {notice && <div className="message success">{notice}</div>}
      </div>
      {preview && (
        <div className="statement-preview" aria-label="Statement preview">
          <h3>Review before importing</h3>
          <p>
            {preview.result.statement.accountName} /{" "}
            {preview.result.statement.accountId}: closing balance{" "}
            <strong>{money(preview.result.statement.closingBalance)}</strong> on{" "}
            {date(preview.result.statement.closingDate)}.
          </p>
          <p>
            <strong>{preview.result.newCount} new transactions</strong> /{" "}
            {preview.result.duplicateCount} duplicates will be skipped. Preview
            has not saved any data. Confirming replaces the statement balance,
            retains previously imported transactions and leaves any planning
            override unchanged.
          </p>
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>Transaction ID</th>
                  <th>Booked</th>
                  <th>Counterparty</th>
                  <th>Reference</th>
                  <th>Amount / EUR</th>
                  <th>Import status</th>
                </tr>
              </thead>
              <tbody>
                {preview.result.rows.map(({ transaction, duplicate }) => (
                  <tr key={transaction.transactionId}>
                    <td className="mono">{transaction.transactionId}</td>
                    <td>{date(transaction.bookingDate)}</td>
                    <td>{transaction.counterparty}</td>
                    <td>{transaction.reference || "--"}</td>
                    <td>{money(transaction.amount)}</td>
                    <td>{duplicate ? "Duplicate / skip" : "New"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <label className="statement-confirm">
            <input
              type="checkbox"
              checked={acknowledged}
              disabled={busy}
              onChange={(event) => setAcknowledged(event.target.checked)}
            />
            I confirm this closing balance already includes all booked payments
            through the closing date.
          </label>
          <div className="planning-actions">
            <button
              className="primary"
              type="button"
              disabled={busy || !acknowledged}
              onClick={() => void confirm()}
            >
              Confirm statement import
            </button>
            <button type="button" disabled={busy} onClick={invalidate}>
              Discard preview
            </button>
          </div>
        </div>
      )}
    </section>
  );
}
