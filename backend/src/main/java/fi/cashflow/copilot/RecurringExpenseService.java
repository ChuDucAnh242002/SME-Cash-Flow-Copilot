package fi.cashflow.copilot;

import static fi.cashflow.copilot.Models.*;
import java.time.*;
import java.util.UUID;
import org.slf4j.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RecurringExpenseService {
    private static final Logger log = LoggerFactory.getLogger(RecurringExpenseService.class);
    private final FinanceStore store;
    private final TransactionTemplate tx;
    private final Clock clock;

    public RecurringExpenseService(FinanceStore store, TransactionTemplate tx, Clock clock) {
        this.store = store; this.tx = tx; this.clock = clock;
    }

    public void create(RecurringExpenseInput input) {
        tx.executeWithoutResult(status -> {
            store.lock();
            var existing = store.recurringExpenses().stream().filter(rule -> rule.id().equals(input.requestId())).findFirst();
            if (existing.isPresent()) {
                var rule = existing.get();
                if (!rule.counterparty().equals(input.counterparty().strip())
                        || rule.amount().compareTo(input.amount()) != 0
                        || !rule.firstDueDate().equals(input.firstDueDate())
                        || !java.util.Objects.equals(rule.endDate(), input.endDate())) {
                    throw new IllegalStateException("This recurring-expense request ID was already used with different details.");
                }
                return;
            }
            LocalDate today = LocalDate.now(clock);
            if (input.firstDueDate().isBefore(today) || input.firstDueDate().isAfter(today.plusYears(5))) {
                throw new CsvImport.CsvException("First recurring due date must be today or within the next five years. Add historical expenses individually.");
            }
            if (input.endDate() != null && (input.endDate().isBefore(input.firstDueDate()) || input.endDate().getYear() > 9998)) {
                throw new CsvImport.CsvException("End date must be on or after the first due date and before year 9999.");
            }
            store.jdbc().update("""
                    INSERT INTO recurring_expense(id,counterparty,amount,first_due_date,end_date,next_month)
                    VALUES (?,?,?,?,?,?)
                    """, input.requestId(), input.counterparty().strip(), input.amount(), input.firstDueDate(),
                    input.endDate(), input.firstDueDate().withDayOfMonth(1));
            generateLocked();
            store.enqueue("RECURRING_EXPENSE_CHANGED");
        });
    }

    public void stop(UUID id, RevisionInput input) {
        tx.executeWithoutResult(status -> {
            store.lock();
            var rule = store.recurringExpenses().stream().filter(value -> value.id().equals(id))
                    .findFirst().orElseThrow(RecurringExpenseNotFoundException::new);
            if (rule.stopped() || rule.revision() != input.revision()) {
                throw new IllegalStateException("This recurring rule changed or was already stopped. Refresh before continuing.");
            }
            store.jdbc().update("UPDATE recurring_expense SET stopped=true,revision=revision+1 WHERE id=?", id);
            store.enqueue("RECURRING_EXPENSE_CHANGED");
        });
    }

    @Scheduled(fixedDelayString = "${app.recurrence-delay-ms:60000}", initialDelayString = "${app.recurrence-delay-ms:60000}")
    public void scheduledGeneration() {
        try {
            generate();
        } catch (DataIntegrityViolationException | IllegalStateException exception) {
            log.error("Recurring expense generation failed; no partial generation was saved ({})", exception.getClass().getSimpleName());
            tx.executeWithoutResult(status -> {
                store.lock();
                store.jdbc().update("UPDATE workspace SET recurrence_error=? WHERE id=1",
                        "Recurring expense generation failed. Existing plans are preserved. Check server logs and conflicting generated references.");
            });
        }
    }

    public void generate() {
        tx.executeWithoutResult(status -> {
            store.lock();
            generateLocked();
            store.jdbc().update("UPDATE workspace SET recurrence_error=NULL WHERE id=1");
        });
    }

    private void generateLocked() {
        LocalDate through = LocalDate.now(clock).plusDays(89);
        int created = 0;
        for (var rule : store.recurringExpenses()) {
            if (rule.stopped()) continue;
            YearMonth month = YearMonth.from(rule.nextMonth());
            while (true) {
                LocalDate due = monthlyOccurrence(rule.firstDueDate(), month);
                if (due.isAfter(through) || (rule.endDate() != null && due.isAfter(rule.endDate()))) break;
                String reference = "REC-" + rule.id() + "-" + month;
                created += store.jdbc().update("""
                        INSERT INTO document(id,kind,counterparty,reference,normalized_reference,amount,due_date,
                        recurring_expense_id,occurrence_month)
                        VALUES (?,'EXPENSE',?,?,?,?,?,?,?)
                        ON CONFLICT(recurring_expense_id,occurrence_month) WHERE recurring_expense_id IS NOT NULL DO NOTHING
                        """, UUID.randomUUID(), rule.counterparty(), reference, normalizeReference(reference),
                        rule.amount(), due, rule.id(), month.atDay(1));
                month = month.plusMonths(1);
            }
            store.jdbc().update("UPDATE recurring_expense SET next_month=? WHERE id=?", month.atDay(1), rule.id());
        }
        if (created > 0) store.enqueue("RECURRING_EXPENSE_GENERATED");
    }

    public static class RecurringExpenseNotFoundException extends RuntimeException {
        public RecurringExpenseNotFoundException() { super("Recurring expense rule not found."); }
    }
}
