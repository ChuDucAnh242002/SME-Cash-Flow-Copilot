package fi.cashflow.copilot;

import static fi.cashflow.copilot.Models.*;
import static fi.cashflow.copilot.CsvImport.CsvException;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class StatementImportService {
    private final FinanceStore store;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final String mode;

    public StatementImportService(FinanceStore store, TransactionTemplate tx, Clock clock,
                                  @Value("${app.bank-mode}") String mode) {
        this.store = store; this.tx = tx; this.clock = clock; this.mode = mode;
    }

    public StatementPreview preview(StatementInput input, List<StatementRow> rows) {
        return tx.execute(status -> {
            store.lock();
            return inspect(input, rows);
        });
    }

    public SyncResult confirm(StatementInput input, List<StatementRow> rows, long revision) {
        return tx.execute(status -> {
            store.lock();
            StatementPreview preview = inspect(input, rows);
            if (preview.revision() != revision) {
                throw new IllegalStateException("Another statement was imported. Preview this file again before confirming.");
            }
            Instant observed = input.closingDate().atStartOfDay(ZoneId.of("Europe/Helsinki")).toInstant();
            var transactions = rows.stream().map(row -> new BankTransaction(input.accountId(), row.transactionId(),
                    row.bookingDate(), row.amount(), row.reference(), row.counterparty(), null)).toList();
            SyncResult result = store.saveSnapshot(new BankSnapshot(new Account(input.accountId(),
                    input.accountName().strip(), "EUR", input.closingBalance(), observed), transactions), "STATEMENT_IMPORTED");
            store.jdbc().update("""
                    UPDATE workspace SET statement_date=?,statement_revision=statement_revision+1,last_sync=? WHERE id=1
                    """, input.closingDate(), Timestamp.from(clock.instant()));
            return result;
        });
    }

    private StatementPreview inspect(StatementInput input, List<StatementRow> rows) {
        if (!mode.equals("statement")) {
            throw new IllegalStateException("Statement imports require a separate statement-mode workspace. Bank data must not be mixed.");
        }
        if (input.closingDate().isAfter(LocalDate.now(clock))) {
            throw new CsvException("Statement closing date cannot be in the future.");
        }
        var current = store.account();
        var previousDate = store.status().statementDate();
        if (current != null && !current.id().equals(input.accountId())) {
            throw new IllegalStateException("This workspace is bound to another account. Use a new statement workspace for a different account.");
        }
        if (previousDate != null && input.closingDate().isBefore(previousDate)) {
            throw new IllegalStateException("An older closing balance cannot replace a newer statement. Use the latest closing date and balance.");
        }
        if (previousDate != null && input.closingDate().equals(previousDate)
                && current.bookedBalance().compareTo(input.closingBalance()) != 0) {
            throw new IllegalStateException("The closing balance differs from the stored balance for this statement date. Manual review is required.");
        }
        Map<String, BankTransaction> existing = store.transactions().stream()
                .collect(Collectors.toMap(BankTransaction::externalId, Function.identity()));
        List<StatementPreviewRow> previewRows = new ArrayList<>();
        for (StatementRow row : rows) {
            if (row.bookingDate().isAfter(input.closingDate())) {
                throw new CsvException("A transaction booking date is after the statement closing date.");
            }
            var previous = existing.get(row.transactionId());
            if (previous != null && (previous.amount().compareTo(row.amount()) != 0
                    || !previous.bookingDate().equals(row.bookingDate())
                    || !previous.reference().equals(row.reference())
                    || !previous.counterparty().equals(row.counterparty()))) {
                throw new IllegalStateException("A stored transaction with the same ID has different details. Nothing was imported; manual review is required.");
            }
            previewRows.add(new StatementPreviewRow(row, previous != null));
        }
        int duplicates = (int) previewRows.stream().filter(StatementPreviewRow::duplicate).count();
        long revision = store.jdbc().queryForObject("SELECT statement_revision FROM workspace WHERE id=1", Long.class);
        return new StatementPreview(revision, input, List.copyOf(previewRows), rows.size() - duplicates, duplicates);
    }
}
