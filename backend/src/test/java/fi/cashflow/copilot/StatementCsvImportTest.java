package fi.cashflow.copilot;

import static org.assertj.core.api.Assertions.*;
import jakarta.validation.Validation;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class StatementCsvImportTest {
    static final String HEADER = "transactionId,bookingDate,amount,reference,counterparty\n";
    final StatementCsvImport csv = new StatementCsvImport(Validation.buildDefaultValidatorFactory().getValidator());

    MockMultipartFile file(String value) {
        return new MockMultipartFile("file", "statement.csv", "text/csv", value.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void parsesQuotedFieldsSignedAmountsAndMissingReferences() {
        var rows = csv.parse(file(HEADER + " tx-1 ,2026-10-28,123.45,10001,\"Example, Oy\"\r\n"
                + "tx-2,2026-10-29,-89.00,,Software\r\n"));
        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst().transactionId()).isEqualTo("tx-1");
        assertThat(rows.getFirst().counterparty()).isEqualTo("Example, Oy");
        assertThat(rows.getLast().amount()).isEqualByComparingTo("-89");
        assertThat(rows.getLast().reference()).isEmpty();
    }

    @Test
    void rejectsInvalidAmountsDatesColumnsAndRequiredFields() {
        for (String amount : new String[]{"0", "-0.00", "1.001", "1e2", "EUR 10", "1 000", "9999999999", ""}) {
            assertThatThrownBy(() -> csv.parse(file(HEADER + "id,2026-10-29," + amount + ",10001,Company")))
                    .isInstanceOf(CsvImport.CsvException.class).hasMessageContaining("row 1");
        }
        assertThatThrownBy(() -> csv.parse(file("amount,name\n10,Company"))).hasMessageContaining("header");
        assertThatThrownBy(() -> csv.parse(file(HEADER))).hasMessageContaining("no transactions");
        assertThatThrownBy(() -> csv.parse(file(""))).hasMessageContaining("empty");
        assertThatThrownBy(() -> csv.parse(file(HEADER + "id,29/10/2026,1,,Company"))).hasMessageContaining("ISO date");
        assertThatThrownBy(() -> csv.parse(file(HEADER + "id,2026-10-29,1,Company"))).hasMessageContaining("columns");
        assertThatThrownBy(() -> csv.parse(file(HEADER + ",2026-10-29,1,,Company"))).hasMessageContaining("transactionId");
        assertThatThrownBy(() -> csv.parse(file(HEADER + "id,2026-10-29,1,, "))).hasMessageContaining("counterparty");
        assertThatThrownBy(() -> csv.parse(file(HEADER + "id,2026-10-29,1,,\"unterminated")))
                .isInstanceOf(CsvImport.CsvException.class);
    }

    @Test
    void rejectsRepeatedIdsInvalidUtf8OversizeAndRowLimit() {
        assertThatThrownBy(() -> csv.parse(file(HEADER + "id,2026-10-29,1,,Company\nid,2026-10-29,1,,Company")))
                .hasMessageContaining("duplicate transaction ID");
        assertThatThrownBy(() -> csv.parse(new MockMultipartFile("file", "invalid.csv", "text/csv",
                new byte[]{(byte) 0xc3, (byte) 0x28}))).hasMessageContaining("UTF-8");
        assertThatThrownBy(() -> csv.parse(new MockMultipartFile("file", new byte[256 * 1024 + 1])))
                .hasMessageContaining("256 KB");
        StringBuilder rows = new StringBuilder(HEADER);
        for (int i = 0; i < 500; i++) rows.append("id-").append(i).append(",2026-10-29,1,,Company\n");
        assertThat(csv.parse(file(rows.toString()))).hasSize(500);
        rows.append("extra,2026-10-29,1,,Company");
        assertThatThrownBy(() -> csv.parse(file(rows.toString()))).hasMessageContaining("500 transactions");
    }
}
