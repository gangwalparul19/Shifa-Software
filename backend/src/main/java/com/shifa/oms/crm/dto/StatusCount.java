package com.shifa.oms.crm.dto;

import com.shifa.oms.statemachine.OrderStatus;

/**
 * How many of a customer's orders are in a given lifecycle status, for the 360
 * profile's status breakdown (FEATURE-ROADMAP §1.1).
 */
public record StatusCount(
        OrderStatus status,
        long count
) {
}
