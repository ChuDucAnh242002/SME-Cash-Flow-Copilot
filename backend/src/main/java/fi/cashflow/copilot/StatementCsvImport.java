package fi.cashflow.copilot;

import static fi.cashflow.copilot.Models.*;
import static fi.cashflow.copilot.CsvImport.CsvException;
import jakarta.validation.Validator;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.*;
import java.time.*;
import java.util.*;
import org.apache.commons.csv.*;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class StatementCsvImport {
    private static final List<String> HEADERS =
            List.of("transactionId", "bookingDate", "amount", "reference", "counterparty");
    private final Validator validator;

    public StatementCsvImport(Validator validator) { this.validator = validator; }

    public List<StatementRow> parse(MultipartFile file) {
        if (file.isEmpty()) throw new CsvException("Statement CSV is empty.");
        if (file.getSize() > 256 * 1024) throw new CsvException("Statement CSV must be at most 256 KB.");
        List<StatementRow> rows = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        try (Reader reader = new InputStreamReader(file.getInputStream(), decoder);
             CSVParser parser = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).get().parse(reader)) {
            if (!parser.getHeaderNames().equals(HEADERS)) {
                throw new CsvException("Statement CSV header must be exactly: " + String.join(",", HEADERS));
            }
            for (CSVRecord record : parser) {
                if (rows.size() >= 500) throw new CsvException("Import at most 500 transactions per statement.");
                if (record.size() != HEADERS.size()) {
                    throw new CsvException("Statement CSV row " + record.getRecordNumber() + " has the wrong number of columns.");
                }
                StatementRow row;
                try {
                    String amount = record.get("amount").strip();
                    if (!amount.matches("-?[0-9]+(\\.[0-9]{1,2})?")) {
                        throw new IllegalArgumentException("Invalid decimal amount");
                    }
                    row = new StatementRow(record.get("transactionId").strip(),
                            LocalDate.parse(record.get("bookingDate").strip()), new BigDecimal(amount),
                            record.get("reference").strip(), record.get("counterparty").strip());
                } catch (IllegalArgumentException | DateTimeException exception) {
                    throw new CsvException("Statement CSV row " + record.getRecordNumber()
                            + ": use an ISO date and a signed decimal EUR amount (no currency symbols or separators).");
                }
                var violations = validator.validate(row);
                if (!violations.isEmpty()) {
                    throw new CsvException("Statement CSV row " + record.getRecordNumber() + ": "
                            + violations.iterator().next().getPropertyPath() + " " + violations.iterator().next().getMessage());
                }
                if (row.amount().signum() == 0) {
                    throw new CsvException("Statement CSV row " + record.getRecordNumber() + ": transaction amount cannot be zero.");
                }
                if (!ids.add(row.transactionId())) {
                    throw new CsvException("Statement CSV row " + record.getRecordNumber() + ": duplicate transaction ID within this file.");
                }
                rows.add(row);
            }
        } catch (IOException | UncheckedIOException exception) {
            throw new CsvException("Cannot read statement CSV. Use a valid UTF-8 RFC4180 file.");
        }
        if (rows.isEmpty()) throw new CsvException("Statement CSV contains no transactions.");
        return List.copyOf(rows);
    }
}
