package com.shifa.oms.payment;

import com.shifa.oms.order.dto.OrderResponse;
import com.shifa.oms.payment.dto.PaymentDecisionRequest;
import com.shifa.oms.payment.dto.PaymentQueueRow;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Payment verification dashboard endpoints (product-audit §4.4).
 *
 * <p>Restricted to the dedicated {@code PAYMENT_VERIFIER} role and {@code ADMIN}.
 * The queue lists prepaid payments awaiting a check; verify/reject record the
 * decision without changing the order status. The payment screenshot itself is
 * viewed through the existing {@code GET /api/orders/{id}/payment-screenshot}
 * (opened to this role too).
 */
@RestController
@RequestMapping("/api/payments")
@PreAuthorize("hasAnyRole('PAYMENT_VERIFIER','ADMIN')")
public class PaymentVerificationController {

    private final PaymentVerificationService service;

    public PaymentVerificationController(PaymentVerificationService service) {
        this.service = service;
    }

    /** Prepaid payments awaiting verification, oldest first. */
    @GetMapping("/queue")
    public List<PaymentQueueRow> queue() {
        return service.queue();
    }

    /** Mark a payment as genuine. */
    @PostMapping("/{id}/verify")
    public OrderResponse verify(@PathVariable Long id,
                                @Valid @RequestBody(required = false) PaymentDecisionRequest request) {
        return service.verify(id, request == null ? null : request.note());
    }

    /** Flag a payment as not genuine / mismatched. */
    @PostMapping("/{id}/reject")
    public OrderResponse reject(@PathVariable Long id,
                                @Valid @RequestBody(required = false) PaymentDecisionRequest request) {
        return service.reject(id, request == null ? null : request.note());
    }
}
