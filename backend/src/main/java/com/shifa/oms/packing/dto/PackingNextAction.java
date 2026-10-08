package com.shifa.oms.packing.dto;

import com.shifa.oms.statemachine.OrderStatus;

/**
 * The next packing action that may be proposed after resolving an internal-label
 * barcode. This is a preview only; the corresponding mutating endpoint still
 * enforces workflow authority and current state.
 */
public enum PackingNextAction {
    PACK(OrderStatus.PACKED),
    HANDOVER(OrderStatus.HANDED_TO_DELIVERY),
    DISPATCH(OrderStatus.COURIER_ASSIGNED),
    NONE(null);

    private final OrderStatus targetStatus;

    PackingNextAction(OrderStatus targetStatus) {
        this.targetStatus = targetStatus;
    }

    public OrderStatus targetStatus() {
        return targetStatus;
    }

    public static PackingNextAction forCurrentStatus(OrderStatus status) {
        return switch (status) {
            case LABEL_GENERATED -> PACK;
            case PACKED -> HANDOVER;
            case HANDED_TO_DELIVERY -> DISPATCH;
            default -> NONE;
        };
    }
}
