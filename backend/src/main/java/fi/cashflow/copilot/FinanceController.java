package fi.cashflow.copilot;

import static fi.cashflow.copilot.Models.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
@Validated
public class FinanceController {
    private final FinanceService service;
    private final CsvImport csv;

    public FinanceController(FinanceService service, CsvImport csv) { this.service = service; this.csv = csv; }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @GetMapping("/dashboard")
    public Dashboard dashboard(@RequestParam(defaultValue = "30") @Min(7) @Max(90) int days,
                               @RequestParam(defaultValue = "0") @Min(0) @Max(60) int incomeDelayDays) {
        return service.dashboard(days, incomeDelayDays);
    }

    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public void create(@Valid @RequestBody DocumentInput input) { service.create(input); }

    @PostMapping(value = "/documents/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Integer> importCsv(@RequestParam MultipartFile file) {
        var inputs = csv.parse(file);
        service.createBatch(inputs);
        return Map.of("imported", inputs.size());
    }

    @PostMapping("/bank/sync")
    public SyncResult sync() { return service.sync(); }

    @PostMapping("/demo/seed")
    @ResponseStatus(HttpStatus.CREATED)
    public void seed() { service.seedDemo(); }
}
