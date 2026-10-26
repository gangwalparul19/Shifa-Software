package com.shifa.oms.order.dto;

import com.shifa.oms.order.OrderPayment;
import com.shifa.oms.order.PaymentMethod;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Safe payment transaction projection; storage keys and full account numbers stay private. */
public record OrderPaymentResponse(
        Long id,
        BigDecimal amountReceived,
        PaymentMethod paymentMethod,
        Long bankAccountId,
        String bankAccountName,
        String bankAccountMasked,
        String utr,
        LocalDateTime capturedAt,
        boolean hasScreenshot) {

    public static OrderPaymentResponse from(OrderPayment payment) {
        return new OrderPaymentResponse(payment.getId(), payment.getAmountReceived(), payment.getPaymentMethod(),
                payment.getBankAccountId(), payment.getBankAccountNameSnapshot(),
                payment.getBankAccountMaskedSnapshot(), payment.getUtr(), payment.getCapturedAt(),
                payment.getScreenshotKey() != null && !payment.getScreenshotKey().isBlank());
    }
}
