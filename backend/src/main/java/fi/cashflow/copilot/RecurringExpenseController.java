package fi.cashflow.copilot;

import static fi.cashflow.copilot.Models.*;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/recurring-expenses")
public class RecurringExpenseController {
    private final RecurringExpenseService recurring;

    public RecurringExpenseController(RecurringExpenseService recurring) { this.recurring = recurring; }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void create(@Valid @RequestBody RecurringExpenseInput input) { recurring.create(input); }

    @PostMapping("/{id}/stop")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void stop(@PathVariable UUID id, @Valid @RequestBody RevisionInput input) { recurring.stop(id, input); }
}
