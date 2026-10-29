package fi.cashflow.copilot;

import static fi.cashflow.copilot.Models.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentReviewService {
    private final FinanceStore store;
    private final TransactionTemplate tx;

    public PaymentReviewService(FinanceStore store, TransactionTemplate tx) {
        this.store = store; this.tx = tx;
    }

    public void decide(PaymentReviewInput input, String actor) {
        tx.executeWithoutResult(status -> {
            store.lock();
            store.reconcile();
            BankTransaction current = find(input.accountId(), input.transactionId());
            if (current.reviewRevision() != input.revision()) {
                throw new IllegalStateException("This payment's match changed. Close the review, refresh and reopen it.");
            }
            MatchMode next;
            UUID targetId = null;
            switch (input.action()) {
                case LINK -> {
                    if (current.documentId() != null) {
                        throw new IllegalStateException("Remove the existing match before linking this payment to another document.");
                    }
                    if (input.documentId() == null) {
                        throw new IllegalStateException("Choose a document to link.");
                    }
                    Document document = store.documents().stream().filter(d -> d.id().equals(input.documentId()))
                            .findFirst().orElseThrow(FinanceService.DocumentNotFoundException::new);
                    if (document.cancelled()) throw new IllegalStateException("Cancelled documents cannot receive payments.");
                    if (current.amount().signum() == 0
                            || (document.kind() == Kind.INVOICE) != (current.amount().signum() > 0)) {
                        throw new IllegalStateException("Payment direction must match the document: receipts for invoices, expenses for outgoing payments.");
                    }
                    if (current.amount().abs().compareTo(document.remaining()) > 0) {
                        throw new IllegalStateException("The whole payment exceeds the document's unpaid amount. Splitting and overpayments are not supported.");
                    }
                    targetId = document.id();
                    next = MatchMode.MANUAL;
                }
                case UNLINK -> {
                    if (current.documentId() == null) throw new IllegalStateException("This payment is already unmatched.");
                    requireNoDocument(input);
                    next = MatchMode.EXCLUDED;
                }
                case RESUME -> {
                    requireNoDocument(input);
                    if (current.matchMode() != MatchMode.EXCLUDED) {
                        throw new IllegalStateException("Only a payment held unmatched can resume automatic matching. Remove its match first.");
                    }
                    next = MatchMode.AUTO;
                }
                default -> throw new IllegalStateException("Unsupported review action.");
            }
            store.jdbc().update("""
                    UPDATE payment_review SET mode=?,document_id=?,revision=revision+1
                    WHERE account_id=? AND transaction_id=?
                    """, next.name(), targetId, input.accountId(), input.transactionId());
            store.reconcile();
            BankTransaction updated = find(input.accountId(), input.transactionId());
            store.jdbc().update("""
                    INSERT INTO payment_review_audit(account_id,transaction_id,action,previous_mode,
                    previous_document_id,previous_document_label,next_mode,next_document_id,next_document_label,reason,actor)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    """, input.accountId(), input.transactionId(), input.action().name(), current.matchMode().name(),
                    current.documentId(), label(current.documentId()), next.name(), updated.documentId(),
                    label(updated.documentId()), input.reason().strip(), actor);
            store.enqueue("PAYMENT_REVIEWED");
        });
    }

    public List<PaymentReviewAudit> history(String accountId, String transactionId) {
        return tx.execute(status -> {
            store.lock();
            find(accountId, transactionId);
            return store.jdbc().query("""
                    SELECT * FROM payment_review_audit WHERE account_id=? AND transaction_id=? ORDER BY id DESC
                    """, (rs, row) -> new PaymentReviewAudit(rs.getLong("id"),
                    ReviewAction.valueOf(rs.getString("action")), MatchMode.valueOf(rs.getString("previous_mode")),
                    rs.getObject("previous_document_id", UUID.class), rs.getString("previous_document_label"),
                    MatchMode.valueOf(rs.getString("next_mode")), rs.getObject("next_document_id", UUID.class),
                    rs.getString("next_document_label"), rs.getString("reason"), rs.getString("actor"),
                    FinanceStore.instant(rs, "occurred_at")), accountId, transactionId);
        });
    }

    private BankTransaction find(String accountId, String transactionId) {
        return store.transactions().stream()
                .filter(t -> t.accountId().equals(accountId) && t.externalId().equals(transactionId))
                .findFirst().orElseThrow(PaymentNotFoundException::new);
    }

    private String label(UUID id) {
        if (id == null) return null;
        return store.documents().stream().filter(d -> d.id().equals(id))
                .map(d -> d.counterparty() + " / " + d.reference()).findFirst()
                .orElseThrow(FinanceService.DocumentNotFoundException::new);
    }

    private void requireNoDocument(PaymentReviewInput input) {
        if (input.documentId() != null) throw new IllegalStateException("A document must only be supplied when linking a payment.");
    }

    public static class PaymentNotFoundException extends RuntimeException {
        public PaymentNotFoundException() { super("Payment not found."); }
    }
}
