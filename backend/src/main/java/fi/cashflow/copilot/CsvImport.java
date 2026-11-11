package fi.cashflow.copilot;

import static fi.cashflow.copilot.Models.*;
import jakarta.validation.Validator;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.apache.commons.csv.*;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class CsvImport {
    private static final List<String> HEADERS = List.of("kind", "counterparty", "reference", "amount", "dueDate");
    private final Validator validator;

    public CsvImport(Validator validator) { this.validator = validator; }

    public List<DocumentInput> parse(MultipartFile file) {
        if (file.isEmpty()) throw new CsvException("CSV file is empty.");
        List<DocumentInput> inputs = new ArrayList<>();
        try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).get().parse(reader)) {
            if (!parser.getHeaderNames().equals(HEADERS)) {
                throw new CsvException("CSV header must be exactly: " + String.join(",", HEADERS));
            }
            for (CSVRecord row : parser) {
                if (inputs.size() >= 200) throw new CsvException("Import at most 200 documents per file.");
                if (row.size() != HEADERS.size()) throw new CsvException("CSV row " + row.getRecordNumber() + " has the wrong number of columns.");
                DocumentInput input;
                try {
                    input = new DocumentInput(Kind.valueOf(row.get("kind").strip()), row.get("counterparty"),
                            row.get("reference"), new BigDecimal(row.get("amount").strip()),
                            LocalDate.parse(row.get("dueDate").strip()));
                } catch (IllegalArgumentException | DateTimeException exception) {
                    throw new CsvException("CSV row " + row.getRecordNumber() + ": invalid kind, amount or ISO date.");
                }
                var violations = validator.validate(input);
                if (!violations.isEmpty()) {
                    throw new CsvException("CSV row " + row.getRecordNumber() + ": " + violations.iterator().next().getMessage());
                }
                inputs.add(input);
            }
        } catch (IOException | UncheckedIOException exception) {
            throw new CsvException("Cannot read CSV. Use a valid UTF-8 RFC4180 file.");
        }
        if (inputs.isEmpty()) throw new CsvException("CSV contains no documents.");
        return List.copyOf(inputs);
    }

    public static class CsvException extends RuntimeException {
        public CsvException(String message) { super(message); }
    }
}
