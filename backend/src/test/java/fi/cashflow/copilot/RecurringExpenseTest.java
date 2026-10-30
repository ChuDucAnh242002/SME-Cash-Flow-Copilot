package fi.cashflow.copilot;

import static org.assertj.core.api.Assertions.*;
import static fi.cashflow.copilot.Models.monthlyOccurrence;
import java.time.*;
import org.junit.jupiter.api.Test;

class RecurringExpenseTest {
    @Test
    void preservesOriginalDayThroughShortMonthsLeapYearsAndYearBoundaries() {
        LocalDate first = LocalDate.of(2026, 1, 31);
        assertThat(monthlyOccurrence(first, YearMonth.of(2026, 2))).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(monthlyOccurrence(first, YearMonth.of(2026, 3))).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(monthlyOccurrence(first, YearMonth.of(2028, 2))).isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(monthlyOccurrence(first, YearMonth.of(2027, 1))).isEqualTo(LocalDate.of(2027, 1, 31));
        LocalDate thirtieth = LocalDate.of(2026, 4, 30);
        assertThat(monthlyOccurrence(thirtieth, YearMonth.of(2026, 5))).isEqualTo(LocalDate.of(2026, 5, 30));
    }
}
