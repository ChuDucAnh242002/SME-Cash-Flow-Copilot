import { money } from "./format";

export function PlanningPanel({
  balance,
  hasBank,
  busy,
  onSave,
}: {
  balance: number | null;
  hasBank: boolean;
  busy: boolean;
  onSave: (balance: string | null) => void;
}) {
  return (
    <section className="panel planning-panel" aria-label="Planning balance">
      <div>
        <p className="eyebrow">
          {balance === null
            ? "BANK-BASED OUTLOOK"
            : "PLANNING SCENARIO / NOT A BANK BALANCE"}
        </p>
        <h2>
          {balance === null
            ? "Plan with a realistic opening balance"
            : `Scenario opening balance: ${money(balance)}`}
        </h2>
        <p>
          A workspace-wide, saved what-if balance. This never changes the
          reported bank balance. A bank sync will not overwrite it.
        </p>
      </div>
      <form
        key={balance ?? "bank"}
        onSubmit={(event) => {
          event.preventDefault();
          const value = new FormData(event.currentTarget).get("balance");
          if (typeof value === "string") onSave(value);
        }}
      >
        <label>
          Planning opening balance / EUR
          <input
            name="balance"
            type="number"
            step=".01"
            min="-999999999.99"
            max="999999999.99"
            defaultValue={balance ?? ""}
            placeholder="12000.00"
            required
            disabled={busy}
          />
        </label>
        <div className="planning-actions">
          <button className="primary" type="submit" disabled={busy}>
            Save planning balance
          </button>
          {balance !== null && (
            <button type="button" disabled={busy} onClick={() => onSave(null)}>
              {hasBank ? "Use bank balance" : "Clear planning balance"}
            </button>
          )}
        </div>
      </form>
    </section>
  );
}
