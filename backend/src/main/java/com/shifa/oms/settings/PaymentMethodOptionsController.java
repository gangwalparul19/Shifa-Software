package com.shifa.oms.settings;

import com.shifa.oms.settings.dto.PaymentMethodOption;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Active payment methods used by order entry (same audience as the receiving-account lookup). */
@RestController
@RequestMapping("/api/payment-methods")
@PreAuthorize("hasAnyRole('ADMIN','SALESPERSON','TEAM_LEAD')")
public class PaymentMethodOptionsController {

    private final PaymentMethodService service;

    public PaymentMethodOptionsController(PaymentMethodService service) { this.service = service; }

    @GetMapping
    public List<PaymentMethodOption> active() { return service.listActiveOptions(); }
}
