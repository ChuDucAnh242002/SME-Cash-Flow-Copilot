package fi.cashflow.copilot;

import static fi.cashflow.copilot.Models.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FinanceStore {
    private final JdbcTemplate jdbc;

    public FinanceStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public JdbcTemplate jdbc() { return jdbc; }

    public List<Document> documents() {
        return jdbc.query("""
                SELECT d.*, COALESCE(SUM(a.amount),0) AS paid_amount
                FROM document d LEFT JOIN allocation a ON a.document_id = d.id
                GROUP BY d.id ORDER BY d.due_date, d.created_at, d.id
                """, (rs, row) -> new Document(rs.getObject("id", UUID.class), Kind.valueOf(rs.getString("kind")),
                rs.getString("counterparty"), rs.getString("reference"), rs.getBigDecimal("amount"),
                rs.getObject("due_date", LocalDate.class), rs.getBigDecimal("paid_amount"),
                rs.getObject("expected_date", LocalDate.class), rs.getBoolean("cancelled"), rs.getLong("revision"),
                rs.getObject("recurring_expense_id", UUID.class), rs.getObject("occurrence_month", LocalDate.class)));
    }

    public List<RecurringExpense> recurringExpenses() {
        return jdbc.query("SELECT * FROM recurring_expense ORDER BY created_at,id", (rs, row) ->
                new RecurringExpense(rs.getObject("id", UUID.class), rs.getString("counterparty"),
                        rs.getBigDecimal("amount"), rs.getObject("first_due_date", LocalDate.class),
                        rs.getObject("end_date", LocalDate.class), rs.getObject("next_month", LocalDate.class),
                        rs.getBoolean("stopped"), rs.getLong("revision")));
    }

    public BigDecimal planningBalance() {
        return jdbc.queryForObject("SELECT planning_balance FROM workspace WHERE id=1", BigDecimal.class);
    }

    public void setPlanningBalance(BigDecimal balance) {
        jdbc.update("UPDATE workspace SET planning_balance=? WHERE id=1", balance);
    }

    public void updateDocument(UUID id, DocumentInput input) {
        jdbc.update("""
                UPDATE document SET kind=?,counterparty=?,reference=?,normalized_reference=?,amount=?,due_date=?,
                  expected_date=?,revision=revision+1 WHERE id=?
                """, input.kind().name(), input.counterparty().strip(), input.reference().strip(),
                normalizeReference(input.reference()), input.amount(), input.dueDate(), input.expectedDate(), id);
    }

    public void cancelDocument(UUID id) {
        jdbc.update("UPDATE document SET cancelled=true,revision=revision+1 WHERE id=?", id);
    }

    public List<BankTransaction> transactions() {
        Map<String, Document> byReference = new HashMap<>();
        for (Document document : documents()) byReference.put(normalizeReference(document.reference()), document);
        return jdbc.query("""
                SELECT t.*, a.document_id, COALESCE(r.mode,'AUTO') AS match_mode,
                    COALESCE(r.revision,0) AS review_revision FROM bank_transaction t
                LEFT JOIN allocation a ON a.account_id=t.account_id AND a.transaction_id=t.external_id
                LEFT JOIN payment_review r ON r.account_id=t.account_id AND r.transaction_id=t.external_id
                ORDER BY t.booking_date, t.external_id
                """, (rs, row) -> {
                    UUID documentId = rs.getObject("document_id", UUID.class);
                    MatchMode mode = MatchMode.valueOf(rs.getString("match_mode"));
                    BigDecimal amount = rs.getBigDecimal("amount");
                    String reference = rs.getString("reference");
                    return new BankTransaction(rs.getString("account_id"), rs.getString("external_id"),
                            rs.getObject("booking_date", LocalDate.class), amount, reference, rs.getString("counterparty"),
                            documentId, mode, rs.getLong("review_revision"),
                            explanation(documentId, mode, amount, reference, byReference));
                });
    }

    private MatchExplanation explanation(UUID documentId, MatchMode mode, BigDecimal amount, String reference,
                                         Map<String, Document> byReference) {
        if (documentId != null) return mode == MatchMode.MANUAL
                ? MatchExplanation.MATCHED_MANUALLY : MatchExplanation.MATCHED_AUTOMATICALLY;
        if (mode == MatchMode.EXCLUDED) return MatchExplanation.EXCLUDED_BY_USER;
        if (amount.signum() == 0) return MatchExplanation.ZERO_AMOUNT;
        if (normalizeReference(reference).isEmpty()) return MatchExplanation.MISSING_REFERENCE;
        Document document = byReference.get(normalizeReference(reference));
        if (document == null) return MatchExplanation.UNKNOWN_REFERENCE;
        if (document.cancelled()) return MatchExplanation.CANCELLED_DOCUMENT;
        if ((document.kind() == Kind.INVOICE) != (amount.signum() > 0)) return MatchExplanation.DIRECTION_MISMATCH;
        if (amount.abs().compareTo(document.remaining()) > 0) return MatchExplanation.EXCESS_AMOUNT;
        return MatchExplanation.RECONCILIATION_PENDING;
    }

    public Account account() {
        return jdbc.query("SELECT * FROM account", (rs, row) ->
                new Account(rs.getString("id"), rs.getString("name"), rs.getString("currency"),
                        rs.getBigDecimal("booked_balance"), instant(rs, "observed_at")))
                .stream().findFirst().orElse(null);
    }

    public Status status() {
        return jdbc.queryForObject("""
                SELECT w.*,(SELECT count(*) FROM outbox o LEFT JOIN processed_event p ON p.id=o.id
                  WHERE p.id IS NULL) AS pending,
                (SELECT last_error FROM outbox WHERE published_at IS NULL AND last_error IS NOT NULL
                  ORDER BY created_at LIMIT 1) AS event_error,
                (SELECT count(*) FROM outbox o LEFT JOIN processed_event p ON p.id=o.id
                  WHERE p.id IS NULL AND o.published_at IS NULL) AS unpublished,
                (SELECT count(*) FROM outbox o LEFT JOIN processed_event p ON p.id=o.id
                  WHERE p.id IS NULL AND o.published_at IS NOT NULL) AS awaiting,
                (SELECT min(o.created_at) FROM outbox o LEFT JOIN processed_event p ON p.id=o.id
                  WHERE p.id IS NULL) AS oldest_pending,
                (SELECT count(*) FROM consumer_failure) AS failed_partitions
                FROM workspace w WHERE id=1
                """, (rs, row) -> new Status(rs.getString("bank_mode"), instant(rs, "last_sync"),
                rs.getString("sync_error"), instant(rs, "last_reconciled"), rs.getLong("pending"), rs.getString("event_error"),
                rs.getObject("statement_date", LocalDate.class), rs.getString("recurrence_error"),
                new PipelineStatus(rs.getLong("unpublished"), rs.getLong("awaiting"), instant(rs, "oldest_pending"),
                        rs.getLong("failed_partitions"), rs.getLong("failed_partitions") > 0
                        ? "Payment reconciliation failed. The affected Kafka partition is retained for retry. Check processing diagnostics and server logs."
                        : null)));
    }

    public void lock() { jdbc.queryForObject("SELECT id FROM workspace WHERE id=1 FOR UPDATE", Integer.class); }

    public void insertDocument(DocumentInput input) {
        jdbc.update("""
                INSERT INTO document(id,kind,counterparty,reference,normalized_reference,amount,due_date,expected_date)
                VALUES (?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), input.kind().name(), input.counterparty().strip(), input.reference().strip(),
                normalizeReference(input.reference()), input.amount(), input.dueDate(), input.expectedDate());
    }

    public void enqueue(String type) {
        jdbc.update("INSERT INTO outbox(id,type) VALUES (?,?)", UUID.randomUUID(), type);
    }

    public SyncResult saveSnapshot(BankSnapshot snapshot) {
        return saveSnapshot(snapshot, "BANK_SYNCED");
    }

    public SyncResult saveSnapshot(BankSnapshot snapshot, String eventType) {
        Account account = snapshot.account();
        Account current = account();
        if (current != null && !current.id().equals(account.id())) {
            throw new IllegalStateException("This workspace is bound to another account. Use a new database for a different account.");
        }
        jdbc.update("""
                INSERT INTO account(id,name,currency,booked_balance,observed_at) VALUES (?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET name=excluded.name,booked_balance=excluded.booked_balance,
                    observed_at=excluded.observed_at
                """, account.id(), account.name(), account.currency(), account.bookedBalance(), Timestamp.from(account.observedAt()));
        int imported = 0;
        for (BankTransaction transaction : snapshot.transactions()) {
            imported += jdbc.update("""
                    INSERT INTO bank_transaction(account_id,external_id,booking_date,amount,reference,counterparty)
                    VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING
                    """, transaction.accountId(), transaction.externalId(), transaction.bookingDate(),
                    transaction.amount(), transaction.reference(), transaction.counterparty());
            BankTransaction existing = jdbc.queryForObject("""
                    SELECT * FROM bank_transaction WHERE account_id=? AND external_id=?
                    """, (rs, row) -> new BankTransaction(rs.getString("account_id"), rs.getString("external_id"),
                    rs.getObject("booking_date", LocalDate.class), rs.getBigDecimal("amount"), rs.getString("reference"),
                    rs.getString("counterparty"), null), transaction.accountId(), transaction.externalId());
            if (existing == null || existing.amount().compareTo(transaction.amount()) != 0
                    || !existing.bookingDate().equals(transaction.bookingDate())
                    || !existing.reference().equals(transaction.reference())) {
                throw new IllegalStateException("A previously imported bank transaction changed. Manual review is required.");
            }
        }
        jdbc.update("UPDATE workspace SET last_sync=?,sync_error=NULL WHERE id=1", Timestamp.from(account.observedAt()));
        enqueue(eventType);
        return new SyncResult(imported, snapshot.transactions().size());
    }

    public void reconcile() {
        jdbc.update("""
                INSERT INTO payment_review(account_id,transaction_id)
                SELECT account_id,external_id FROM bank_transaction ON CONFLICT DO NOTHING
                """);
        var before = transactions();
        Map<TransactionKey, UUID> manual = new HashMap<>();
        jdbc.query("SELECT account_id,transaction_id,document_id FROM payment_review WHERE mode='MANUAL'", rs -> {
            manual.put(new TransactionKey(rs.getString("account_id"), rs.getString("transaction_id")),
                    rs.getObject("document_id", UUID.class));
        });
        // Reserve manual decisions first; automatic replay must never replace them.
        jdbc.update("DELETE FROM allocation");
        Map<String, Document> byReference = new HashMap<>();
        Map<UUID, Document> byId = new HashMap<>();
        for (Document document : documents()) {
            byId.put(document.id(), document);
            if (!document.cancelled()) byReference.put(normalizeReference(document.reference()), document);
        }
        Map<UUID, BigDecimal> allocated = new HashMap<>();
        for (BankTransaction transaction : before) {
            if (transaction.matchMode() != MatchMode.MANUAL) continue;
            Document document = byId.get(manual.get(new TransactionKey(transaction.accountId(), transaction.externalId())));
            if (document == null || document.cancelled() || transaction.amount().signum() == 0
                    || (document.kind() == Kind.INVOICE) != (transaction.amount().signum() > 0)
                    || allocated.getOrDefault(document.id(), BigDecimal.ZERO).add(transaction.amount().abs())
                        .compareTo(document.amount()) > 0) {
                throw new IllegalStateException("A manual payment decision conflicts with its document. Review the match before changing this document.");
            }
            allocate(transaction, document, allocated);
        }
        for (BankTransaction transaction : before) {
            if (transaction.matchMode() != MatchMode.AUTO) continue;
            Document document = byReference.get(normalizeReference(transaction.reference()));
            if (document == null || transaction.amount().signum() == 0) continue;
            if ((document.kind() == Kind.INVOICE) != (transaction.amount().signum() > 0)) continue;
            BigDecimal amount = transaction.amount().abs();
            BigDecimal total = allocated.getOrDefault(document.id(), BigDecimal.ZERO).add(amount);
            if (total.compareTo(document.amount()) > 0) continue;
            allocate(transaction, document, allocated);
        }
        Map<TransactionKey, UUID> after = new HashMap<>();
        for (BankTransaction transaction : transactions()) {
            after.put(new TransactionKey(transaction.accountId(), transaction.externalId()), transaction.documentId());
        }
        for (BankTransaction transaction : before) {
            if (!Objects.equals(transaction.documentId(), after.get(new TransactionKey(transaction.accountId(), transaction.externalId())))) {
                jdbc.update("UPDATE payment_review SET revision=revision+1 WHERE account_id=? AND transaction_id=?",
                        transaction.accountId(), transaction.externalId());
            }
        }
        jdbc.update("UPDATE workspace SET last_reconciled=now() WHERE id=1");
    }

    private void allocate(BankTransaction transaction, Document document, Map<UUID, BigDecimal> allocated) {
        BigDecimal amount = transaction.amount().abs();
        jdbc.update("INSERT INTO allocation(account_id,transaction_id,document_id,amount) VALUES (?,?,?,?)",
                transaction.accountId(), transaction.externalId(), document.id(), amount);
        allocated.merge(document.id(), amount, BigDecimal::add);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
