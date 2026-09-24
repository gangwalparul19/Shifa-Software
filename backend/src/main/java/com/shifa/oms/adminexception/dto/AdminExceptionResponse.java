package com.shifa.oms.adminexception.dto;

import com.shifa.oms.statemachine.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Read-only work queue for the ADMIN exception center. */
public record AdminExceptionResponse(
        int total,
        Map<String, Long> countsByCategory,
        List<AdminExceptionItem> items
) {

    /** One actionable exception from an existing operational queue. */
    public record AdminExceptionItem(
            String category,
            String severity,
            String title,
            String detail,
            Long orderId,
            String orderCode,
            String customerName,
            String customerMobile,
            OrderStatus orderStatus,
            BigDecimal amount,
            LocalDateTime createdAt,
            String actionPath,
            // Name of the salesperson who punched the order (resolved from
            // created_by; full name, else username; null for non-order-backed
            // rows like INSIGHT), so the admin sees who triggered each exception.
            String salespersonName
    ) {
    }
}
