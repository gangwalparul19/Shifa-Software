package com.shifa.oms.payment;

import com.shifa.oms.payment.dto.ConfirmPaymentRequest;
import com.shifa.oms.payment.dto.ConfirmPaymentResponse;
import com.shifa.oms.payment.dto.InitiatePaymentRequest;
import com.shifa.oms.payment.dto.InitiatePaymentResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public online-payment endpoints (Phase E). Like {@code /api/checkout} these
 * are open to anonymous callers (see {@code SecurityConfig}): a guest can pay
 * for the order they just placed. The gateway session and, critically, the
 * signature verification are handled entirely server-side.
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /** Create a payment session for an order (validates it is payable). */
    @PostMapping("/initiate")
    public InitiatePaymentResponse initiate(@Valid @RequestBody InitiatePaymentRequest request) {
        return paymentService.initiate(request.orderId());
    }

    /** Verify a payment result; on success the order is marked fully paid. */
    @PostMapping("/confirm")
    public ConfirmPaymentResponse confirm(@Valid @RequestBody ConfirmPaymentRequest request) {
        return paymentService.confirm(request);
    }
}
