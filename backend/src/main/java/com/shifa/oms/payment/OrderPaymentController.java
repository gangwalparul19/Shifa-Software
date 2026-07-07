package com.shifa.oms.payment;

import com.shifa.oms.payment.dto.PaymentTransactionResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin/accountant visibility of an order's online-payment transactions
 * (Phase E): {@code GET /api/orders/{id}/payments}. Restricted to
 * {@code ADMIN}/{@code ACCOUNTANT} via method security; unauthenticated callers
 * get 401 and other roles 403.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderPaymentController {

    private final PaymentService paymentService;

    public OrderPaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /** List the payment transactions recorded for an order, newest first. */
    @GetMapping("/{id}/payments")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public List<PaymentTransactionResponse> payments(@PathVariable Long id) {
        return paymentService.transactionsForOrder(id);
    }
}
