package fi.cashflow.copilot;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class Models {
    private Models() {}

    public enum Kind { INVOICE, EXPENSE }

    public record DocumentInput(
            @NotNull Kind kind,
            @NotBlank @Size(max = 160) String counterparty,
            @NotBlank @Size(max = 80) String reference,
            @NotNull @DecimalMin("0.01") @DecimalMax("999999999.99") @Digits(integer = 9, fraction = 2) BigDecimal amount,
            @NotNull LocalDate dueDate,
            LocalDate expectedDate) {
        public DocumentInput(Kind kind, String counterparty, String reference, BigDecimal amount, LocalDate dueDate) {
            this(kind, counterparty, reference, amount, dueDate, null);
        }
    }

    public record DocumentUpdate(@NotNull @jakarta.validation.Valid DocumentInput document,
                                 @NotNull @PositiveOrZero Long revision) {}
    public record RevisionInput(@NotNull @PositiveOrZero Long revision) {}
    public record PlanningInput(
            @DecimalMin("-999999999.99") @DecimalMax("999999999.99")
            @Digits(integer = 9, fraction = 2) BigDecimal openingBalance) {}

    public record Document(UUID id, Kind kind, String counterparty, String reference,
                           BigDecimal amount, LocalDate dueDate, BigDecimal paidAmount,
                           LocalDate expectedDate, boolean cancelled, long revision) {
        public Document(UUID id, Kind kind, String counterparty, String reference,
                        BigDecimal amount, LocalDate dueDate, BigDecimal paidAmount) {
            this(id, kind, counterparty, reference, amount, dueDate, paidAmount, null, false, 0);
        }
        public BigDecimal remaining() { return amount.subtract(paidAmount); }
    }

    public record BankTransaction(String accountId, String externalId, LocalDate bookingDate,
                                  BigDecimal amount, String reference, String counterparty, UUID documentId) {}

    public record Account(String id, String name, String currency, BigDecimal bookedBalance, Instant observedAt) {}
    public record BankSnapshot(Account account, List<BankTransaction> transactions) {}
    public record ForecastItem(UUID documentId, String label, BigDecimal amount) {}
    public record ForecastDay(LocalDate date, BigDecimal balance, List<ForecastItem> items) {}
    public record Forecast(int days, int incomeDelayDays, BigDecimal openingBalance, BigDecimal minimumBalance,
                           LocalDate firstShortfall, List<ForecastDay> series, List<String> assumptions) {}
    public record Status(String mode, Instant lastSync, String syncError, Instant lastReconciled,
                         long pendingEvents, String eventError) {}
    public record Dashboard(Status status, Account account, BigDecimal planningBalance, List<Document> documents,
                            List<BankTransaction> transactions, Forecast forecast) {}
    public record FinanceEvent(UUID id, String type, int schemaVersion) {}
    public record SyncResult(int imported, int observed) {}

    public static String normalizeReference(String reference) {
        return reference.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }
}
