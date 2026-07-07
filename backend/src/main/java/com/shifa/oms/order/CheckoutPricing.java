package com.shifa.oms.order;

import com.shifa.oms.common.ValidationException;
import com.shifa.oms.order.domain.LineItem;
import com.shifa.oms.order.domain.Money;
import com.shifa.oms.order.domain.PaymentCalculator;
import com.shifa.oms.order.dto.CheckoutRequest;
import com.shifa.oms.product.Product;
import com.shifa.oms.product.ProductRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared storefront cart pricing (Phase D). Prices a set of checkout items
 * strictly from the products' current sale prices — the single source of truth
 * used by both {@link OrderService} at checkout and {@link com.shifa.oms.coupon.CouponService}
 * when previewing a coupon discount — so a coupon is always validated against
 * the exact same subtotal the order would be priced at.
 *
 * <p>Customers never set prices; each line is {@code salePrice × quantity} with
 * exact {@link Money} arithmetic.
 */
@Service
public class CheckoutPricing {

    private final ProductRepository productRepository;

    public CheckoutPricing(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    /** Computes the cart subtotal for the given items (server-priced). */
    public Money subtotal(List<CheckoutRequest.CheckoutItemRequest> items) {
        if (items == null || items.isEmpty()) {
            throw new ValidationException("The cart must contain at least one item.");
        }
        List<LineItem> lines = new ArrayList<>(items.size());
        for (CheckoutRequest.CheckoutItemRequest item : items) {
            Product product = productRepository.findById(item.productId())
                    .orElseThrow(() -> new ValidationException(
                            "Product " + item.productId() + " does not exist."));
            lines.add(new LineItem(product.getName(), item.quantity(), Money.of(product.getSalePrice())));
        }
        return PaymentCalculator.totalAmount(lines);
    }
}
