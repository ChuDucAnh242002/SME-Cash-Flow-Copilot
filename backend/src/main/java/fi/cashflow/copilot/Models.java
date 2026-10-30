package fi.cashflow.copilot;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class Models {
    private Models() {}

    public enum Kind { INVOICE, EXPENSE }
    public enum MatchMode { AUTO, MANUAL, EXCLUDED }
    public enum ReviewAction { LINK, UNLINK, RESUME }
    public record TransactionKey(String accountId, String transactionId) {}
    public enum MatchExplanation {
        MATCHED_AUTOMATICALLY, MATCHED_MANUALLY, EXCLUDED_BY_USER, MISSING_REFERENCE,
        UNKNOWN_REFERENCE, CANCELLED_DOCUMENT, DIRECTION_MISMATCH, EXCESS_AMOUNT,
        ZERO_AMOUNT, RECONCILIATION_PENDING
    }

    public record PaymentReviewInput(
            @NotBlank String accountId, @NotBlank String transactionId,
            @NotNull ReviewAction action, UUID documentId,
            @NotNull @PositiveOrZero Long revision,
            @NotBlank @Size(max = 500) String reason) {}
    public record PaymentReviewAudit(long id, ReviewAction action, MatchMode previousMode,
                                    UUID previousDocumentId, String previousDocumentLabel,
                                    MatchMode nextMode, UUID nextDocumentId, String nextDocumentLabel,
                                    String reason, String actor, Instant occurredAt) {}

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
    public record RecurringExpenseInput(
            @NotNull UUID requestId,
            @NotBlank @Size(max = 160) String counterparty,
            @NotNull @DecimalMin("0.01") @DecimalMax("999999999.99")
            @Digits(integer = 9, fraction = 2) BigDecimal amount,
            @NotNull LocalDate firstDueDate, LocalDate endDate) {}
    public record RecurringExpense(UUID id, String counterparty, BigDecimal amount,
                                   LocalDate firstDueDate, LocalDate endDate, LocalDate nextMonth,
                                   boolean stopped, long revision) {
        @com.fasterxml.jackson.annotation.JsonProperty
        public LocalDate nextDueDate() {
            return monthlyOccurrence(firstDueDate, YearMonth.from(nextMonth));
        }
    }
    public record PlanningInput(
            @DecimalMin("-999999999.99") @DecimalMax("999999999.99")
            @Digits(integer = 9, fraction = 2) BigDecimal openingBalance) {}

    public record StatementInput(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._:-]{1,128}") String accountId,
            @NotBlank @Size(max = 160) String accountName,
            @NotNull @DecimalMin("-999999999.99") @DecimalMax("999999999.99")
            @Digits(integer = 9, fraction = 2) BigDecimal closingBalance,
            @NotNull LocalDate closingDate) {}
    public record StatementRow(
            @NotBlank @Size(max = 128) String transactionId,
            @NotNull LocalDate bookingDate,
            @NotNull @DecimalMin("-999999999.99") @DecimalMax("999999999.99")
            @Digits(integer = 9, fraction = 2) BigDecimal amount,
            @NotNull @Size(max = 80) String reference,
            @NotBlank @Size(max = 160) String counterparty) {}
    public record StatementPreviewRow(StatementRow transaction, boolean duplicate) {}
    public record StatementPreview(long revision, StatementInput statement,
                                   List<StatementPreviewRow> rows, int newCount, int duplicateCount) {}

    public record Document(UUID id, Kind kind, String counterparty, String reference,
                           BigDecimal amount, LocalDate dueDate, BigDecimal paidAmount,
                           LocalDate expectedDate, boolean cancelled, long revision,
                           UUID recurringExpenseId, LocalDate occurrenceMonth) {
        public Document(UUID id, Kind kind, String counterparty, String reference,
                        BigDecimal amount, LocalDate dueDate, BigDecimal paidAmount,
                        LocalDate expectedDate, boolean cancelled, long revision) {
            this(id, kind, counterparty, reference, amount, dueDate, paidAmount, expectedDate,
                    cancelled, revision, null, null);
        }
        public Document(UUID id, Kind kind, String counterparty, String reference,
                        BigDecimal amount, LocalDate dueDate, BigDecimal paidAmount) {
            this(id, kind, counterparty, reference, amount, dueDate, paidAmount, null, false, 0, null, null);
        }
        public BigDecimal remaining() { return amount.subtract(paidAmount); }
    }

    public record BankTransaction(String accountId, String externalId, LocalDate bookingDate,
                                  BigDecimal amount, String reference, String counterparty, UUID documentId,
                                  MatchMode matchMode, long reviewRevision, MatchExplanation explanation) {
        public BankTransaction(String accountId, String externalId, LocalDate bookingDate,
                               BigDecimal amount, String reference, String counterparty, UUID documentId) {
            this(accountId, externalId, bookingDate, amount, reference, counterparty, documentId,
                    MatchMode.AUTO, 0, null);
        }
    }

    public record Account(String id, String name, String currency, BigDecimal bookedBalance, Instant observedAt) {}
    public record BankSnapshot(Account account, List<BankTransaction> transactions) {}
    public record ForecastItem(UUID documentId, String label, BigDecimal amount) {}
    public record ForecastDay(LocalDate date, BigDecimal balance, List<ForecastItem> items) {}
    public record ForecastContribution(LocalDate date, UUID documentId, String label, BigDecimal amount) {}
    public record ShortfallExplanation(LocalDate date, boolean openingAlreadyNegative,
                                       BigDecimal balanceBeforeDay, BigDecimal receipts, BigDecimal outgoings,
                                       BigDecimal closingBalance, List<ForecastContribution> contributions) {}
    public record Forecast(int days, int incomeDelayDays, BigDecimal openingBalance, BigDecimal minimumBalance,
                           LocalDate firstShortfall, List<ForecastDay> series, List<String> assumptions,
                           BigDecimal expectedReceipts, BigDecimal plannedOutgoings, BigDecimal closingBalance,
                           ShortfallExplanation shortfallExplanation) {}
    public record Status(String mode, Instant lastSync, String syncError, Instant lastReconciled,
                         long pendingEvents, String eventError, LocalDate statementDate, String recurrenceError,
                         PipelineStatus pipeline) {}
    public record PipelineStatus(long unpublishedEvents, long awaitingReconciliation, Instant oldestPendingAt,
                                 long failedPartitions, String consumerError) {}
    public record Dashboard(Status status, Account account, BigDecimal planningBalance, List<Document> documents,
                            List<BankTransaction> transactions, Forecast forecast, List<RecurringExpense> recurringExpenses) {}
    public record FinanceEvent(UUID id, String type, int schemaVersion) {}
    public record SyncResult(int imported, int observed) {}

    public static String normalizeReference(String reference) {
        return reference.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    public static LocalDate monthlyOccurrence(LocalDate first, YearMonth month) {
        return month.atDay(Math.min(first.getDayOfMonth(), month.lengthOfMonth()));
    }
}
