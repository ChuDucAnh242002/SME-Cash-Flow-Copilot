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
                rs.getObject("due_date", LocalDate.class), rs.getBigDecimal("paid_amount")));
    }

    public List<BankTransaction> transactions() {
        return jdbc.query("""
                SELECT t.*, a.document_id FROM bank_transaction t
                LEFT JOIN allocation a ON a.account_id=t.account_id AND a.transaction_id=t.external_id
                ORDER BY t.booking_date, t.external_id
                """, (rs, row) -> new BankTransaction(rs.getString("account_id"), rs.getString("external_id"),
                rs.getObject("booking_date", LocalDate.class), rs.getBigDecimal("amount"),
                rs.getString("reference"), rs.getString("counterparty"), rs.getObject("document_id", UUID.class)));
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
                  ORDER BY created_at LIMIT 1) AS event_error FROM workspace w WHERE id=1
                """, (rs, row) -> new Status(rs.getString("bank_mode"), instant(rs, "last_sync"),
                rs.getString("sync_error"), instant(rs, "last_reconciled"), rs.getLong("pending"), rs.getString("event_error")));
    }

    public void lock() { jdbc.queryForObject("SELECT id FROM workspace WHERE id=1 FOR UPDATE", Integer.class); }

    public void insertDocument(DocumentInput input) {
        jdbc.update("""
                INSERT INTO document(id,kind,counterparty,reference,normalized_reference,amount,due_date)
                VALUES (?,?,?,?,?,?,?)
                """, UUID.randomUUID(), input.kind().name(), input.counterparty().strip(), input.reference().strip(),
                normalizeReference(input.reference()), input.amount(), input.dueDate());
    }

    public void enqueue(String type) {
        jdbc.update("INSERT INTO outbox(id,type) VALUES (?,?)", UUID.randomUUID(), type);
    }

    public SyncResult saveSnapshot(BankSnapshot snapshot) {
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
        enqueue("BANK_SYNCED");
        return new SyncResult(imported, snapshot.transactions().size());
    }

    public void reconcile() {
        // Rebuild deterministically so invoices entered after a sync can match historical receipts.
        jdbc.update("DELETE FROM allocation");
        Map<String, Document> byReference = new HashMap<>();
        for (Document document : documents()) byReference.put(normalizeReference(document.reference()), document);
        Map<UUID, BigDecimal> allocated = new HashMap<>();
        for (BankTransaction transaction : transactions()) {
            Document document = byReference.get(normalizeReference(transaction.reference()));
            if (document == null || transaction.amount().signum() == 0) continue;
            if ((document.kind() == Kind.INVOICE) != (transaction.amount().signum() > 0)) continue;
            BigDecimal amount = transaction.amount().abs();
            BigDecimal total = allocated.getOrDefault(document.id(), BigDecimal.ZERO).add(amount);
            if (total.compareTo(document.amount()) > 0) continue;
            jdbc.update("INSERT INTO allocation(account_id,transaction_id,document_id,amount) VALUES (?,?,?,?)",
                    transaction.accountId(), transaction.externalId(), document.id(), amount);
            allocated.put(document.id(), total);
        }
        jdbc.update("UPDATE workspace SET last_reconciled=now() WHERE id=1");
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
