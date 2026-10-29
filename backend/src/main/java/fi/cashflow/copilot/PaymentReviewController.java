package fi.cashflow.copilot;

import static fi.cashflow.copilot.Models.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reconciliation")
@Validated
public class PaymentReviewController {
    private final PaymentReviewService reviews;

    public PaymentReviewController(PaymentReviewService reviews) { this.reviews = reviews; }

    @PostMapping("/decisions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decide(@Valid @RequestBody PaymentReviewInput input, Principal principal) {
        reviews.decide(input, principal.getName());
    }

    @GetMapping("/history")
    public List<PaymentReviewAudit> history(@RequestParam @NotBlank String accountId,
                                           @RequestParam @NotBlank String transactionId) {
        return reviews.history(accountId, transactionId);
    }
}
