package com.shifa.oms.settings;

import com.shifa.oms.settings.dto.PaymentAccountOption;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Safe active receiving-account lookup used by salesperson order entry. */
@RestController
@RequestMapping("/api/payment-accounts")
@PreAuthorize("hasAnyRole('ADMIN','SALESPERSON','TEAM_LEAD')")
public class PaymentAccountController {

    private final BankAccountService service;

    public PaymentAccountController(BankAccountService service) { this.service = service; }

    @GetMapping
    public List<PaymentAccountOption> active() { return service.activePaymentAccounts(); }
}
