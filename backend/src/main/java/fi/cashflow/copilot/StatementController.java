package fi.cashflow.copilot;

import static fi.cashflow.copilot.Models.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/statements")
@Validated
public class StatementController {
    private final StatementCsvImport csv;
    private final StatementImportService statements;

    public StatementController(StatementCsvImport csv, StatementImportService statements) {
        this.csv = csv; this.statements = statements;
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public StatementPreview preview(@RequestParam MultipartFile file, @Valid @ModelAttribute StatementInput input) {
        return statements.preview(input, csv.parse(file));
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SyncResult confirm(@RequestParam MultipartFile file, @Valid @ModelAttribute StatementInput input,
                              @RequestParam @PositiveOrZero long revision) {
        return statements.confirm(input, csv.parse(file), revision);
    }
}
