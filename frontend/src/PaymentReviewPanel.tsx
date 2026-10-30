import { useEffect, useRef, useState } from "react";
import { request } from "./api";
import { date, money } from "./format";
import { eligibleDocuments, matchExplanation } from "./reconciliation";
import type {
  Document,
  PaymentReviewAudit,
  ReviewAction,
  Transaction,
} from "./types";

const actionLabels: Record<ReviewAction, string> = {
  LINK: "Link payment",
  UNLINK: "Remove match",
  RESUME: "Resume automatic matching",
};

export function PaymentReviewPanel({
  transaction,
  current,
  documents,
  busy,
  onBusyChange,
  onClose,
  onSaved,
}: {
  transaction: Transaction;
  current: Transaction | undefined;
  documents: Document[];
  busy: boolean;
  onBusyChange: (busy: boolean) => void;
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const [history, setHistory] = useState<PaymentReviewAudit[] | null>(null);
  const [historyError, setHistoryError] = useState("");
  const [historyAttempt, setHistoryAttempt] = useState(0);
  const [error, setError] = useState("");
  const [action, setAction] = useState<ReviewAction>(
    transaction.documentId ? "UNLINK" : "LINK",
  );
  const [documentId, setDocumentId] = useState("");
  const [reason, setReason] = useState("");
  const [confirming, setConfirming] = useState(false);
  const heading = useRef<HTMLHeadingElement>(null);
  const confirmation = useRef<HTMLParagraphElement>(null);
  const correctionButton = useRef<HTMLButtonElement>(null);
  const errorMessage = useRef<HTMLDivElement>(null);
  const stale =
    !current || current.reviewRevision !== transaction.reviewRevision;
  const eligible = eligibleDocuments(transaction, documents);
  const chosen = documents.find((document) => document.id === documentId);
  const matched = documents.find(
    (document) => document.id === transaction.documentId,
  );
  const validTarget =
    action !== "LINK" ||
    eligible.some((document) => document.id === documentId);

  useEffect(() => {
    heading.current?.focus();
  }, []);

  useEffect(() => {
    if (confirming) confirmation.current?.focus();
  }, [confirming]);

  useEffect(() => {
    if (error) errorMessage.current?.focus();
  }, [error]);

  useEffect(() => {
    let active = true;
    setHistory(null);
    setHistoryError("");
    const query = new URLSearchParams({
      accountId: transaction.accountId,
      transactionId: transaction.externalId,
    });
    void request<PaymentReviewAudit[]>(`/api/reconciliation/history?${query}`)
      .then((result) => {
        if (active) setHistory(result);
      })
      .catch((failure: unknown) => {
        if (active)
          setHistoryError(
            failure instanceof Error
              ? failure.message
              : "Could not load payment history.",
          );
      });
    return () => {
      active = false;
    };
  }, [transaction.accountId, transaction.externalId, historyAttempt]);

  async function save() {
    onBusyChange(true);
    setError("");
    try {
      await request("/api/reconciliation/decisions", "POST", {
        accountId: transaction.accountId,
        transactionId: transaction.externalId,
        revision: transaction.reviewRevision,
        action,
        documentId: action === "LINK" ? documentId : null,
        reason: reason.trim(),
      });
      await onSaved();
    } catch (failure) {
      setError(
        failure instanceof Error ? failure.message : "Payment review failed.",
      );
      setConfirming(false);
    } finally {
      onBusyChange(false);
    }
  }

  return (
    <section
      className="payment-review"
      aria-label="Payment review"
      id="payment-review"
    >
      <div className="panel-heading">
        <h3 ref={heading} tabIndex={-1}>
          Review payment {transaction.externalId}
        </h3>
        <button type="button" disabled={busy} onClick={onClose}>
          Close review
        </button>
      </div>
      <p>
        {transaction.counterparty} / {date(transaction.bookingDate)} /{" "}
        <strong>{money(transaction.amount)}</strong>
      </p>
      <p>{matchExplanation[transaction.explanation]}</p>
      {matched && (
        <p>
          Linked to{" "}
          <strong>
            {matched.counterparty} / {matched.reference}
          </strong>
          .
        </p>
      )}
      <p className="form-note">
        Only the whole payment can be linked to one active document. Receipts
        match invoices; outgoing payments match expenses. Splits, overpayments
        and refunds are not supported. No bank or statement facts are changed.
      </p>
      {stale && (
        <div className="message error" role="alert">
          This payment's match changed. Close the review, refresh and reopen it.
        </div>
      )}
      {error && (
        <div
          className="message error"
          role="alert"
          ref={errorMessage}
          tabIndex={-1}
        >
          {error}
        </div>
      )}
      <form
        onSubmit={(event) => {
          event.preventDefault();
          setConfirming(true);
          setError("");
        }}
      >
        <fieldset disabled={busy || stale || confirming}>
          <legend>Correction</legend>
          <label>
            Review action
            <select
              value={action}
              onChange={(event) =>
                setAction(
                  event.target.value === "RESUME"
                    ? "RESUME"
                    : event.target.value === "UNLINK"
                      ? "UNLINK"
                      : "LINK",
                )
              }
            >
              {transaction.documentId ? (
                <option value="UNLINK">Remove match</option>
              ) : (
                <option value="LINK">Link payment</option>
              )}
              {transaction.matchMode === "EXCLUDED" && (
                <option value="RESUME">Resume automatic matching</option>
              )}
            </select>
          </label>
          {action === "LINK" && (
            <label>
              Document to link
              <select
                required
                value={documentId}
                onChange={(event) => setDocumentId(event.target.value)}
              >
                <option value="">Choose an eligible document</option>
                {eligible.map((document) => (
                  <option key={document.id} value={document.id}>
                    {document.counterparty} / {document.reference} / unpaid{" "}
                    {money(document.amount - document.paidAmount)}
                  </option>
                ))}
              </select>
            </label>
          )}
          {action === "LINK" && eligible.length === 0 && (
            <p>
              No eligible documents. Add an active document with the same
              payment direction and enough unpaid amount, then reopen this
              review.
            </p>
          )}
          <label>
            Reason for correction
            <textarea
              required
              maxLength={500}
              rows={3}
              value={reason}
              onChange={(event) => setReason(event.target.value)}
            />
          </label>
          <button
            ref={correctionButton}
            type="submit"
            className="primary"
            disabled={!validTarget || !reason.trim()}
          >
            Review correction
          </button>
        </fieldset>
      </form>
      {confirming && (
        <div
          className="cancel-confirmation"
          role="region"
          aria-label="Confirm payment correction"
        >
          <p ref={confirmation} tabIndex={-1}>
            <strong>{actionLabels[action]}</strong>
            {action === "LINK" && chosen
              ? `: ${money(transaction.amount)} to ${chosen.counterparty} / ${chosen.reference}.`
              : "."}
            {action === "UNLINK" &&
              " The document becomes unpaid by this amount. This payment stays unmatched through syncs and Kafka replay until you explicitly resume automatic matching or link it manually."}
            {action === "RESUME" &&
              " Automatic reference matching runs immediately and on future reconciliation events. A match is not guaranteed."}
            {action === "LINK" &&
              " This manual link is protected from automatic matching."}
          </p>
          <p>
            Reason: {reason.trim()}. Your signed-in username and timestamp will
            be recorded.
          </p>
          <button
            type="button"
            className="primary"
            disabled={busy || stale || !validTarget}
            onClick={() => void save()}
          >
            Confirm correction
          </button>
          <button
            type="button"
            disabled={busy}
            onClick={() => {
              setConfirming(false);
              window.setTimeout(() => correctionButton.current?.focus(), 0);
            }}
          >
            Back to review
          </button>
        </div>
      )}
      <h4>Payment review history</h4>
      <p className="form-note">
        Manual corrections only. Automatic matching is derived; it does not
        create review entries.
      </p>
      {historyError && (
        <div className="message error" role="alert">
          {historyError}{" "}
          <button
            type="button"
            onClick={() => setHistoryAttempt((value) => value + 1)}
          >
            Retry history
          </button>
        </div>
      )}
      {!history && !historyError && <p>Loading review history...</p>}
      {history?.length === 0 && <p>No manual corrections recorded.</p>}
      {history && history.length > 0 && (
        <ol className="review-history">
          {history.map((entry) => (
            <li key={entry.id}>
              <strong>{actionLabels[entry.action]}</strong> / {entry.actor} /{" "}
              <time dateTime={entry.occurredAt}>
                {new Date(entry.occurredAt).toLocaleString("en-GB", {
                  timeZone: "Europe/Helsinki",
                })}{" "}
                Helsinki
              </time>
              <p>
                {entry.previousDocumentLabel ?? "Unmatched"} (
                {entry.previousMode}) &rarr;{" "}
                {entry.nextDocumentLabel ?? "Unmatched"} ({entry.nextMode})
              </p>
              <p>Reason: {entry.reason}</p>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
