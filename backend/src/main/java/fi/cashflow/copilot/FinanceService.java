package fi.cashflow.copilot;

import static fi.cashflow.copilot.Models.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class FinanceService implements ApplicationRunner {
    private final FinanceStore store;
    private final BankPort bank;
    private final ForecastEngine engine;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final String mode;

    public FinanceService(FinanceStore store, BankPort bank, ForecastEngine engine, TransactionTemplate tx,
                          Clock clock, @Value("${app.bank-mode}") String mode) {
        this.store = store; this.bank = bank; this.engine = engine; this.tx = tx; this.clock = clock; this.mode = mode;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        tx.executeWithoutResult(status -> {
            store.jdbc().update("INSERT INTO workspace(id,bank_mode) VALUES (1,?) ON CONFLICT DO NOTHING", mode);
            if (!store.status().mode().equals(mode)) {
                throw new IllegalStateException("Bank mode differs from the persisted workspace. Use a new database; never mix demo and sandbox data.");
            }
        });
    }

    public Dashboard dashboard(int days, int delay) {
        return tx.execute(status -> {
            // Same lock as writes: documents, allocations and balance form a consistent snapshot.
            store.lock();
            Account account = store.account();
            BigDecimal planning = store.planningBalance();
            var documents = store.documents();
            return new Dashboard(store.status(), account, planning, documents, store.transactions(),
                    engine.forecast(planning != null ? planning : account == null ? BigDecimal.ZERO : account.bookedBalance(),
                            documents, LocalDate.now(clock), days, delay), store.recurringExpenses());
        });
    }

    public void setPlanningBalance(PlanningInput input) {
        tx.executeWithoutResult(status -> { store.lock(); store.setPlanningBalance(input.openingBalance()); });
    }

    public void update(UUID id, DocumentUpdate update) {
        tx.executeWithoutResult(status -> {
            store.lock();
            // Reconcile inside the lock before checking paid amounts, even if Kafka is behind.
            store.reconcile();
            Document current = editableDocument(id, update.revision());
            DocumentInput input = update.document();
            if (current.recurringExpenseId() != null && input.kind() != Kind.EXPENSE) {
                throw new IllegalStateException("A recurring occurrence must remain an expense.");
            }
            if (current.paidAmount().signum() > 0) {
                if (input.kind() != current.kind()
                        || !normalizeReference(input.reference()).equals(normalizeReference(current.reference()))) {
                    throw new IllegalStateException("A matched document's type and payment reference cannot be changed.");
                }
                if (input.amount().compareTo(current.paidAmount()) < 0) {
                    throw new IllegalStateException("Amount cannot be lower than the payments already matched to this document.");
                }
            }
            store.updateDocument(id, input);
            store.reconcile();
            store.enqueue("DOCUMENT_UPDATED");
        });
    }

    public void cancel(UUID id, RevisionInput input) {
        tx.executeWithoutResult(status -> {
            store.lock();
            store.reconcile();
            Document current = editableDocument(id, input.revision());
            if (current.paidAmount().signum() > 0) {
                throw new IllegalStateException("A document with matched payments cannot be cancelled. Review its payments first.");
            }
            store.cancelDocument(id);
            store.reconcile();
            store.enqueue("DOCUMENT_CANCELLED");
        });
    }

    private Document editableDocument(UUID id, long revision) {
        Document current = store.documents().stream().filter(document -> document.id().equals(id)).findFirst()
                .orElseThrow(DocumentNotFoundException::new);
        if (current.cancelled()) throw new IllegalStateException("Cancelled documents cannot be changed.");
        if (current.revision() != revision) {
            throw new IllegalStateException("This document changed in another session. Close the form, refresh and reopen it.");
        }
        return current;
    }

    public static class DocumentNotFoundException extends RuntimeException {
        public DocumentNotFoundException() { super("Document not found."); }
    }

    public void create(DocumentInput input) {
        tx.executeWithoutResult(status -> { store.lock(); store.insertDocument(input); store.enqueue("DOCUMENT_CREATED"); });
    }

    public void createBatch(java.util.List<DocumentInput> inputs) {
        tx.executeWithoutResult(status -> {
            store.lock();
            inputs.forEach(store::insertDocument);
            store.enqueue("DOCUMENT_CREATED");
        });
    }

    public SyncResult sync() {
        if (mode.equals("statement")) {
            throw new IllegalStateException("Bank sync is disabled in statement mode. Preview and import a statement instead.");
        }
        // Serialize manual syncs across replicas without holding a database transaction during HTTP requests.
        // The PostgreSQL session advisory lock is released even when an upstream request fails.
        return store.jdbc().execute((org.springframework.jdbc.core.ConnectionCallback<SyncResult>) connection -> {
            try (var lock = connection.prepareStatement("SELECT pg_try_advisory_lock(792041)")) {
                try (var result = lock.executeQuery()) {
                    result.next();
                    if (!result.getBoolean(1)) throw new SyncBusyException();
                }
            }
            try {
                BankSnapshot snapshot = bank.fetch();
                return tx.execute(status -> { store.lock(); return store.saveSnapshot(snapshot); });
            } catch (BankAccessException | IllegalStateException exception) {
                tx.executeWithoutResult(status -> store.jdbc().update(
                        "UPDATE workspace SET sync_error=? WHERE id=1", exception.getMessage()));
                throw exception;
            } finally {
                try (var unlock = connection.prepareStatement("SELECT pg_advisory_unlock(792041)")) { unlock.execute(); }
            }
        });
    }

    public void seedDemo() {
        if (!mode.equals("demo")) throw new IllegalStateException("Demo scenarios are disabled in Nordea mode.");
        tx.executeWithoutResult(status -> {
            store.lock();
            if (store.jdbc().queryForObject("SELECT count(*) FROM document", Long.class) != 0) {
                throw new IllegalStateException("Demo scenario can only be loaded into a workspace without documents.");
            }
            LocalDate today = LocalDate.now(clock);
            store.insertDocument(new DocumentInput(Kind.INVOICE, "Aurora Design Oy", "10001", new BigDecimal("3200"), today));
            store.insertDocument(new DocumentInput(Kind.EXPENSE, "Helsinki Workspace", "20001", new BigDecimal("1200"), today));
            store.insertDocument(new DocumentInput(Kind.EXPENSE, "Team salaries", "20002", new BigDecimal("9800"), today.plusDays(5)));
            store.insertDocument(new DocumentInput(Kind.EXPENSE, "Equipment supplier", "20003", new BigDecimal("4800"), today.plusDays(9)));
            store.insertDocument(new DocumentInput(Kind.INVOICE, "Baltic Software Oy", "10002", new BigDecimal("8500"), today.plusDays(14)));
            store.insertDocument(new DocumentInput(Kind.INVOICE, "North Studio Oy", "10003", new BigDecimal("2400"), today.plusDays(21)));
            store.enqueue("DEMO_LOADED");
        });
    }

    public static class SyncBusyException extends RuntimeException {
        public SyncBusyException() { super("A bank sync is already running. Wait for it to finish."); }
    }
}
