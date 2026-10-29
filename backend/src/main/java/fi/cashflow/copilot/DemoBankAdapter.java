package fi.cashflow.copilot;

import static fi.cashflow.copilot.Models.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.bank-mode", havingValue = "demo")
public class DemoBankAdapter implements BankPort {
    private final Clock clock;

    public DemoBankAdapter(Clock clock) { this.clock = clock; }

    public BankSnapshot fetch() {
        String id = "DEMO-FI-EUR";
        // Fixed dates and IDs keep repeat syncs idempotent, including across midnight.
        return new BankSnapshot(new Account(id, "Demo Studio Oy", "EUR", new BigDecimal("12500.00"), clock.instant()),
                List.of(
                        new BankTransaction(id, "demo-receipt-1", LocalDate.of(2026, 10, 20),
                                new BigDecimal("3200.00"), "10001", "Aurora Design Oy", null),
                        new BankTransaction(id, "demo-rent-1", LocalDate.of(2026, 10, 21),
                                new BigDecimal("-1200.00"), "20001", "Helsinki Workspace", null),
                        new BankTransaction(id, "demo-unmatched-1", LocalDate.of(2026, 10, 22),
                                new BigDecimal("-89.00"), "", "Software subscription", null)));
    }
}
