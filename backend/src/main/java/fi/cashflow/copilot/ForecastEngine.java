package fi.cashflow.copilot;

import static fi.cashflow.copilot.Models.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ForecastEngine {
    public Forecast forecast(BigDecimal opening, List<Document> documents, LocalDate today, int days, int delayDays) {
        Map<LocalDate, List<ForecastItem>> scheduled = new HashMap<>();
        for (Document document : documents) {
            if (document.cancelled() || document.remaining().signum() <= 0) continue;
            LocalDate date = document.expectedDate() == null ? document.dueDate() : document.expectedDate();
            if (document.kind() == Kind.INVOICE) date = date.plusDays(delayDays);
            date = date.isBefore(today) ? today : date;
            BigDecimal amount = document.kind() == Kind.INVOICE ? document.remaining() : document.remaining().negate();
            scheduled.computeIfAbsent(date, ignored -> new ArrayList<>())
                    .add(new ForecastItem(document.id(), document.counterparty() + " / " + document.reference(), amount));
        }
        BigDecimal balance = opening;
        BigDecimal minimum = opening;
        LocalDate shortfall = opening.signum() < 0 ? today : null;
        List<ForecastDay> series = new ArrayList<>();
        for (int day = 0; day < days; day++) {
            LocalDate date = today.plusDays(day);
            List<ForecastItem> items = scheduled.getOrDefault(date, List.of());
            for (ForecastItem item : items) balance = balance.add(item.amount());
            minimum = minimum.min(balance);
            if (shortfall == null && balance.signum() < 0) shortfall = date;
            series.add(new ForecastDay(date, balance, List.copyOf(items)));
        }
        BigDecimal receipts = BigDecimal.ZERO;
        BigDecimal outgoings = BigDecimal.ZERO;
        for (ForecastDay day : series) {
            for (ForecastItem item : day.items()) {
                if (item.amount().signum() > 0) receipts = receipts.add(item.amount());
                else outgoings = outgoings.subtract(item.amount());
            }
        }
        ShortfallExplanation explanation = null;
        if (shortfall != null) {
            BigDecimal beforeDay = opening;
            List<ForecastContribution> contributions = new ArrayList<>();
            for (ForecastDay day : series) {
                BigDecimal dayReceipts = BigDecimal.ZERO;
                BigDecimal dayOutgoings = BigDecimal.ZERO;
                for (ForecastItem item : day.items()) {
                    if (item.amount().signum() > 0) dayReceipts = dayReceipts.add(item.amount());
                    else dayOutgoings = dayOutgoings.subtract(item.amount());
                    contributions.add(new ForecastContribution(day.date(), item.documentId(), item.label(), item.amount()));
                }
                if (day.date().equals(shortfall)) {
                    explanation = new ShortfallExplanation(shortfall, opening.signum() < 0,
                            beforeDay, dayReceipts, dayOutgoings, day.balance(), List.copyOf(contributions));
                    break;
                }
                beforeDay = day.balance();
            }
        }
        return new Forecast(days, delayDays, opening, minimum, shortfall, series, List.of(
                "EUR only. Opening balance uses the saved planning override when set; otherwise the last observed booked account balance (bank snapshot or uploaded statement). Imported transactions are not added again.",
                "Only unpaid portions of active invoices and expenses are forecast. Cancelled items are excluded. Monthly rules create ordinary expense documents; no automatic tax calculations.",
                "Receipts are assumed on their expected date (or due date) plus " + delayDays + " days; past movements are placed today.",
                "Daily closing balances, not intraday liquidity. No guarantee that customers pay, and no credit facilities included.",
                "Dates use Europe/Helsinki. A stale opening balance makes the forecast stale. Uploaded statements are not bank-verified; later activity is unknown until another import."),
                receipts, outgoings, balance, explanation);
    }
}
