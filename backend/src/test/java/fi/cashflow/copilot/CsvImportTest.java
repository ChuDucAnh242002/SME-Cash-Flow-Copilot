package fi.cashflow.copilot;

import static org.assertj.core.api.Assertions.*;
import jakarta.validation.Validation;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class CsvImportTest {
    final CsvImport csv = new CsvImport(Validation.buildDefaultValidatorFactory().getValidator());

    MockMultipartFile file(String value) {
        return new MockMultipartFile("file", "invoices.csv", "text/csv", value.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void parsesQuotedCompanyNamesAndAcceptsOverdueItems() {
        var inputs = csv.parse(file("kind,counterparty,reference,amount,dueDate\r\n"
                + "INVOICE,\"Example, Oy\",10001,123.45,2020-01-01\r\n"));
        assertThat(inputs).hasSize(1);
        assertThat(inputs.getFirst().counterparty()).isEqualTo("Example, Oy");
        assertThat(inputs.getFirst().amount()).isEqualByComparingTo("123.45");
    }

    @Test
    void rejectsInvalidHeaderNegativeAmountsAndMalformedDates() {
        assertThatThrownBy(() -> csv.parse(file("name,amount\nTest,10"))).hasMessageContaining("header");
        assertThatThrownBy(() -> csv.parse(file("kind,counterparty,reference,amount,dueDate\n"
                + "EXPENSE,Company,1,-1,2026-10-29"))).hasMessageContaining("row 1");
        assertThatThrownBy(() -> csv.parse(file("kind,counterparty,reference,amount,dueDate\n"
                + "EXPENSE,Company,1,1,29/10/2026"))).hasMessageContaining("ISO date");
    }

    @Test
    void rejectsAnInvalidLaterRowBeforePersistingAnyRows() {
        assertThatThrownBy(() -> csv.parse(file("kind,counterparty,reference,amount,dueDate\n"
                + "INVOICE,Company,1,10,2026-10-29\nINVOICE,Other,2,not-money,2026-10-29")))
                .hasMessageContaining("row 2");
    }
}
