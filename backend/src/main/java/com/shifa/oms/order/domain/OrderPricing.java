package com.shifa.oms.order.domain;

import com.shifa.oms.common.ValidationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure, side-effect-free order pricing + GST engine
 * (product-catalog-pricing-gst Req 6, 7, 8).
 *
 * <p>Prices are GST-INCLUSIVE ([D1]): the per-line GST is the tax component
 * <em>extracted</em> from the (post-discount) line amount, never added on top.
 * The engine:
 * <ol>
 *   <li>sums line totals into a subtotal (GST-inclusive);</li>
 *   <li>resolves the order-level discount (FLAT rupee amount or PERCENT of
 *       subtotal), rejecting invalid values;</li>
 *   <li>apportions the discount across lines proportionally to line totals using
 *       largest-remainder distribution so the shares sum EXACTLY to the discount
 *       (Correctness Property 1);</li>
 *   <li>extracts each line's GST from its discounted net using the line's GST
 *       rate;</li>
 *   <li>aggregates GST overall and per rate, and rounds the payable total to the
 *       nearest whole rupee.</li>
 * </ol>
 *
 * <p>No Spring, no persistence — fully unit-testable.
 */
public final class OrderPricing {

    private static final int SCALE = 2;
    private static final RoundingMode ROUND = RoundingMode.HALF_UP;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private OrderPricing() {
    }

    /** One priced line's input: quantity, applied rate, and (nullable) GST rate percent. */
    public record LineInput(int quantity, BigDecimal rate, BigDecimal gstRate) {
        public BigDecimal lineTotal() {
            return rate.multiply(BigDecimal.valueOf(quantity)).setScale(SCALE, ROUND);
        }
    }

    /** The order-level discount specification. */
    public record DiscountSpec(DiscountType type, BigDecimal value) {
        public static final DiscountSpec NONE = new DiscountSpec(DiscountType.NONE, BigDecimal.ZERO);

        public static DiscountSpec of(DiscountType type, BigDecimal value) {
            return new DiscountSpec(type == null ? DiscountType.NONE : type,
                    value == null ? BigDecimal.ZERO : value);
        }
    }

    /** Per-line pricing result. */
    public record PricedLineResult(BigDecimal lineTotal, BigDecimal discountShare,
                                   BigDecimal netAmount, BigDecimal gstAmount, BigDecimal gstRate) {
    }

    /** The full priced order. */
    public record PricedOrder(BigDecimal subtotal, BigDecimal discount, BigDecimal gstTotal,
                              BigDecimal total, List<PricedLineResult> lines,
                              Map<BigDecimal, BigDecimal> gstByRate) {
    }

    /**
     * Prices an order from its lines and discount spec. Throws
     * {@link ValidationException} for an invalid discount (Req 6.2, 6.3).
     */
    public static PricedOrder compute(List<LineInput> lines, DiscountSpec discount) {
        List<BigDecimal> lineTotals = new ArrayList<>(lines.size());
        BigDecimal subtotal = BigDecimal.ZERO.setScale(SCALE, ROUND);
        for (LineInput line : lines) {
            BigDecimal lt = line.lineTotal();
            lineTotals.add(lt);
            subtotal = subtotal.add(lt);
        }

        BigDecimal discountAmount = resolveDiscount(discount, subtotal);
        List<BigDecimal> shares = apportion(discountAmount, lineTotals, subtotal);

        List<PricedLineResult> results = new ArrayList<>(lines.size());
        Map<BigDecimal, BigDecimal> gstByRate = new LinkedHashMap<>();
        BigDecimal gstTotal = BigDecimal.ZERO.setScale(SCALE, ROUND);
        for (int i = 0; i < lines.size(); i++) {
            LineInput line = lines.get(i);
            BigDecimal lt = lineTotals.get(i);
            BigDecimal share = shares.get(i);
            BigDecimal net = lt.subtract(share).setScale(SCALE, ROUND);
            BigDecimal rate = line.gstRate();
            BigDecimal gst = extractGst(net, rate);
            gstTotal = gstTotal.add(gst);
            if (gst.signum() != 0 && rate != null) {
                BigDecimal key = rate.setScale(SCALE, ROUND);
                gstByRate.merge(key, gst, BigDecimal::add);
            }
            results.add(new PricedLineResult(lt, share, net, gst, rate));
        }

        BigDecimal total = subtotal.subtract(discountAmount).setScale(0, ROUND).setScale(SCALE, ROUND);
        return new PricedOrder(subtotal, discountAmount, gstTotal, total, results, gstByRate);
    }

    /** Resolves the discount to a rupee amount, validating bounds (Req 6.2, 6.3). */
    private static BigDecimal resolveDiscount(DiscountSpec discount, BigDecimal subtotal) {
        DiscountSpec spec = discount == null ? DiscountSpec.NONE : discount;
        DiscountType type = spec.type() == null ? DiscountType.NONE : spec.type();
        BigDecimal value = spec.value() == null ? BigDecimal.ZERO : spec.value();
        return switch (type) {
            case NONE -> BigDecimal.ZERO.setScale(SCALE, ROUND);
            case PERCENT -> {
                if (value.signum() < 0 || value.compareTo(HUNDRED) > 0) {
                    throw new ValidationException("A percentage discount must be between 0 and 100.");
                }
                yield subtotal.multiply(value).divide(HUNDRED, SCALE, ROUND);
            }
            case FLAT -> {
                if (value.signum() < 0) {
                    throw new ValidationException("A discount amount must not be negative.");
                }
                if (value.compareTo(subtotal) > 0) {
                    throw new ValidationException("A discount amount cannot exceed the order subtotal.");
                }
                yield value.setScale(SCALE, ROUND);
            }
        };
    }

    /**
     * Distributes {@code discount} across lines proportionally to their totals so
     * the shares sum EXACTLY to the discount (largest-remainder method). Zero
     * discount or zero subtotal yields all-zero shares.
     */
    private static List<BigDecimal> apportion(BigDecimal discount, List<BigDecimal> lineTotals,
                                              BigDecimal subtotal) {
        int n = lineTotals.size();
        List<BigDecimal> shares = new ArrayList<>(n);
        BigDecimal zero = BigDecimal.ZERO.setScale(SCALE, ROUND);
        if (discount.signum() == 0 || subtotal.signum() == 0 || n == 0) {
            for (int i = 0; i < n; i++) {
                shares.add(zero);
            }
            return shares;
        }
        // Floor each share to 2 dp; track remainders to distribute the leftover paise.
        BigDecimal allocated = zero;
        List<BigDecimal> remainders = new ArrayList<>(n);
        for (BigDecimal lt : lineTotals) {
            BigDecimal exact = discount.multiply(lt).divide(subtotal, 6, ROUND);
            BigDecimal floor = exact.setScale(SCALE, RoundingMode.DOWN);
            shares.add(floor);
            remainders.add(exact.subtract(floor));
            allocated = allocated.add(floor);
        }
        // Leftover paise = discount − Σ floors, handed out one paise at a time to
        // the lines with the largest fractional remainder.
        BigDecimal leftover = discount.subtract(allocated);
        int paiseLeft = leftover.movePointRight(SCALE).setScale(0, ROUND).intValueExact();
        BigDecimal onePaise = new BigDecimal("0.01");
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            order.add(i);
        }
        order.sort((a, b) -> remainders.get(b).compareTo(remainders.get(a)));
        for (int k = 0; k < paiseLeft; k++) {
            int idx = order.get(k % n);
            shares.set(idx, shares.get(idx).add(onePaise));
        }
        return shares;
    }

    /**
     * Extracts the GST component from a GST-inclusive amount ([D1]):
     * {@code gst = net − net / (1 + rate/100)}, HALF_UP to 2 dp. A null or
     * non-positive rate yields zero GST.
     */
    private static BigDecimal extractGst(BigDecimal net, BigDecimal rate) {
        BigDecimal zero = BigDecimal.ZERO.setScale(SCALE, ROUND);
        if (rate == null || rate.signum() <= 0 || net.signum() <= 0) {
            return zero;
        }
        BigDecimal divisor = BigDecimal.ONE.add(rate.divide(HUNDRED, 6, ROUND));
        BigDecimal base = net.divide(divisor, SCALE, ROUND);
        return net.subtract(base).setScale(SCALE, ROUND);
    }
}
