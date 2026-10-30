package fi.cashflow.copilot;

import static fi.cashflow.copilot.Models.*;
import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class ForecastEngineTest {
    final ForecastEngine engine = new ForecastEngine();
    final LocalDate today = LocalDate.of(2026, 10, 29);

    Document document(Kind kind, String amount, String paid, int dueIn) {
        return new Document(UUID.randomUUID(), kind, "Company", "123", new BigDecimal(amount),
                today.plusDays(dueIn), new BigDecimal(paid));
    }

    @Test
    void excludesSettledDocumentsAndForecastsOnlyPartialRemainders() {
        var forecast = engine.forecast(new BigDecimal("1000.00"), List.of(
                document(Kind.INVOICE, "200", "200", 1),
                document(Kind.INVOICE, "300", "100", 2),
                document(Kind.EXPENSE, "1500", "100", 3)), today, 30, 0);
        assertThat(forecast.series()).hasSize(30);
        assertThat(forecast.minimumBalance()).isEqualByComparingTo("-200.00");
        assertThat(forecast.firstShortfall()).isEqualTo(today.plusDays(3));
        assertThat(forecast.series().get(29).balance()).isEqualByComparingTo("-200.00");
    }

    @Test
    void delayingReceiptsChangesTheMeasuredShortfall() {
        var documents = List.of(document(Kind.INVOICE, "500", "0", 2), document(Kind.EXPENSE, "600", "0", 3));
        assertThat(engine.forecast(new BigDecimal("200"), documents, today, 30, 0).firstShortfall()).isNull();
        var delayed = engine.forecast(new BigDecimal("200"), documents, today, 30, 7);
        assertThat(delayed.firstShortfall()).isEqualTo(today.plusDays(3));
        assertThat(delayed.minimumBalance()).isEqualByComparingTo("-400");
    }

    @Test
    void putsOverdueItemsTodayAndExcludesItemsOutsideHorizon() {
        var forecast = engine.forecast(BigDecimal.ZERO, List.of(
                document(Kind.INVOICE, "100", "0", -5),
                document(Kind.INVOICE, "1000", "0", 30)), today, 30, 0);
        assertThat(forecast.series().getFirst().balance()).isEqualByComparingTo("100");
        assertThat(forecast.series().getLast().balance()).isEqualByComparingTo("100");
        assertThat(forecast.minimumBalance()).isEqualByComparingTo("0");
    }

    @Test
    void handlesNegativeOpeningAndExactDecimalAmounts() {
        var forecast = engine.forecast(new BigDecimal("-0.30"), List.of(
                document(Kind.INVOICE, "0.10", "0", 0), document(Kind.INVOICE, "0.20", "0", 0)), today, 7, 0);
        assertThat(forecast.firstShortfall()).isEqualTo(today);
        assertThat(forecast.series().getFirst().balance()).isEqualByComparingTo("0.00");
        assertThat(forecast.minimumBalance()).isEqualByComparingTo("-0.30");
    }

    @Test
    void measuresDailyClosingNotIntradayOrdering() {
        var forecast = engine.forecast(BigDecimal.ZERO, List.of(
                document(Kind.EXPENSE, "100", "0", 0), document(Kind.INVOICE, "100", "0", 0)), today, 7, 0);
        assertThat(forecast.firstShortfall()).isNull();
        assertThat(forecast.series().getFirst().items()).hasSize(2);
    }

    @Test
    void expectedDatesOverrideDueDatesAndCancelledDocumentsNeverAffectForecast() {
        var invoice = new Document(UUID.randomUUID(), Kind.INVOICE, "Customer", "INV", new BigDecimal("100"),
                today.minusDays(2), BigDecimal.ZERO, today.plusDays(5), false, 1);
        var cancelled = new Document(UUID.randomUUID(), Kind.EXPENSE, "Supplier", "VOID", new BigDecimal("1000"),
                today, BigDecimal.ZERO, null, true, 1);
        var forecast = engine.forecast(BigDecimal.ZERO, List.of(invoice, cancelled), today, 30, 7);
        assertThat(forecast.firstShortfall()).isNull();
        assertThat(forecast.series().get(11).balance()).isEqualByComparingTo("0");
        assertThat(forecast.series().get(12).balance()).isEqualByComparingTo("100");
        assertThat(forecast.series().stream().flatMap(day -> day.items().stream()).count()).isEqualTo(1);
        assertThat(invoice.dueDate()).isEqualTo(today.minusDays(2));
    }

    @Test
    void explainsFirstNegativeClosingWithPriorMovementsAndExactHorizonTotals() {
        var rent = document(Kind.EXPENSE, "50", "0", 1);
        var receipt = document(Kind.INVOICE, "30", "10", 2);
        var salaries = document(Kind.EXPENSE, "100", "0", 3);
        var later = document(Kind.INVOICE, "200", "0", 29);
        var outside = document(Kind.EXPENSE, "999", "0", 30);
        var forecast = engine.forecast(new BigDecimal("100"), List.of(rent, receipt, salaries, later, outside), today, 30, 0);
        assertThat(forecast.expectedReceipts()).isEqualByComparingTo("220");
        assertThat(forecast.plannedOutgoings()).isEqualByComparingTo("150");
        assertThat(forecast.closingBalance()).isEqualByComparingTo("170");
        assertThat(forecast.openingBalance().add(forecast.expectedReceipts()).subtract(forecast.plannedOutgoings()))
                .isEqualByComparingTo(forecast.closingBalance());
        var explanation = forecast.shortfallExplanation();
        assertThat(explanation.date()).isEqualTo(today.plusDays(3));
        assertThat(explanation.openingAlreadyNegative()).isFalse();
        assertThat(explanation.balanceBeforeDay()).isEqualByComparingTo("70");
        assertThat(explanation.receipts()).isEqualByComparingTo("0");
        assertThat(explanation.outgoings()).isEqualByComparingTo("100");
        assertThat(explanation.closingBalance()).isEqualByComparingTo("-30");
        assertThat(explanation.contributions()).extracting(ForecastContribution::documentId)
                .containsExactly(rent.id(), receipt.id(), salaries.id());
    }

    @Test
    void negativeOpeningIsExplainedEvenWhenTodaysReceiptsRecoverTheClosingBalance() {
        var forecast = engine.forecast(new BigDecimal("-10"), List.of(document(Kind.INVOICE, "20", "0", 0)), today, 7, 0);
        assertThat(forecast.shortfallExplanation().openingAlreadyNegative()).isTrue();
        assertThat(forecast.shortfallExplanation().balanceBeforeDay()).isEqualByComparingTo("-10");
        assertThat(forecast.shortfallExplanation().closingBalance()).isEqualByComparingTo("10");
        assertThat(forecast.shortfallExplanation().receipts()).isEqualByComparingTo("20");
        assertThat(engine.forecast(BigDecimal.ZERO, List.of(), today, 7, 0).shortfallExplanation()).isNull();
    }
}
