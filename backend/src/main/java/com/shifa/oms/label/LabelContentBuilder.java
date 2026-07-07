package com.shifa.oms.label;

import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderLineItem;
import com.shifa.oms.order.domain.PaymentStatus;

import java.util.List;
import java.util.Objects;

/**
 * Pure content builder that assembles the internal-label {@link InternalLabelContent}
 * model from persisted {@link OrderEntity} aggregates, with no PDF/byte
 * production (design "PDF / label / barcode generation"). Keeping content
 * assembly separate from rendering lets the completeness rules be property-tested
 * directly against the model (Property 18) and the bulk fan-out against a plain
 * list (Property 19).
 *
 * <p>The barcode value is always the order code (Req 10.1). The COD amount is
 * included on the content <em>if and only if</em> the order's payment status is
 * {@code COD} or {@code Partially_Paid}; it is omitted (left {@code null}, with
 * {@code codApplicable=false}) for {@code Fully_Paid} orders (Req 10.2).
 *
 * <p>This class is stateless and has no Spring or persistence dependencies.
 */
public class LabelContentBuilder {

    /**
     * Builds the internal-label content model for a single order (Req 10.1, 10.2).
     *
     * @param order the source order aggregate (never {@code null})
     * @return the assembled, render-agnostic label content
     */
    public InternalLabelContent buildInternal(OrderEntity order) {
        Objects.requireNonNull(order, "order");

        List<InternalLabelContent.LabelLineItem> items = order.getLineItems().stream()
                .map(this::toLabelLine)
                .toList();

        boolean codApplicable = isCodApplicable(order.getPaymentStatus());

        return new InternalLabelContent(
                order.getOrderCode(),
                order.getOrderCode(),
                order.getCustomerName(),
                order.getCustomerMobile(),
                order.getAddressLine(),
                order.getCity(),
                order.getState(),
                order.getPostalCode(),
                items,
                codApplicable,
                codApplicable ? order.getCodAmount() : null);
    }

    /**
     * Builds one internal-label content block per requested order, preserving
     * input order (Req 10.4, Property 19). The result has exactly one block per
     * input order.
     *
     * @param orders the orders to produce labels for (never {@code null})
     * @return one {@link InternalLabelContent} per input order, in order
     */
    public List<InternalLabelContent> buildBulk(List<OrderEntity> orders) {
        Objects.requireNonNull(orders, "orders");
        return orders.stream().map(this::buildInternal).toList();
    }

    /** COD is shown on the label for COD and Partially_Paid orders only (Req 10.2). */
    private boolean isCodApplicable(PaymentStatus status) {
        return status == PaymentStatus.COD || status == PaymentStatus.PARTIALLY_PAID;
    }

    private InternalLabelContent.LabelLineItem toLabelLine(OrderLineItem item) {
        return new InternalLabelContent.LabelLineItem(item.getProductName(), item.getQuantity());
    }
}
