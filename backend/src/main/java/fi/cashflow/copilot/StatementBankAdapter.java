package fi.cashflow.copilot;

import static fi.cashflow.copilot.Models.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.bank-mode", havingValue = "statement")
public class StatementBankAdapter implements BankPort {
    public BankSnapshot fetch() {
        throw new IllegalStateException("Statement workspaces cannot connect to a bank. Import a statement instead.");
    }
}
