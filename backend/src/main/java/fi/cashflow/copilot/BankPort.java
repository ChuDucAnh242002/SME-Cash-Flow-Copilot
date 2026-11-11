package fi.cashflow.copilot;

import fi.cashflow.copilot.Models.BankSnapshot;

public interface BankPort {
    BankSnapshot fetch();
}
