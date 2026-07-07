package com.shifa.oms.coupon;

import com.shifa.oms.common.DuplicateResourceException;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.coupon.domain.CouponCalculator;
import com.shifa.oms.coupon.domain.CouponDiscount;
import com.shifa.oms.coupon.domain.CouponType;
import com.shifa.oms.coupon.dto.CouponRequest;
import com.shifa.oms.coupon.dto.CouponResponse;
import com.shifa.oms.coupon.dto.ValidateCouponRequest;
import com.shifa.oms.coupon.dto.ValidateCouponResponse;
import com.shifa.oms.order.CheckoutPricing;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.domain.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Coupons application service (Phase D).
 *
 * <p>Three responsibilities:
 * <ul>
 *   <li><strong>Preview</strong> ({@link #validate}) — public: prices the cart
 *       (same pricing as checkout) and evaluates a coupon so the storefront can
 *       show the discount and new total before placing the order. Never mutates
 *       usage.</li>
 *   <li><strong>Apply</strong> ({@link #applyToCheckout}) — authoritative,
 *       called from order creation: re-validates the coupon server-side against
 *       the real subtotal and the customer's prior redemptions, rejects with a
 *       clear error when invalid or over-limit, records the redemption
 *       (increments {@code used_count}) and returns the discount to subtract.</li>
 *   <li><strong>Manage</strong> (CRUD + toggle) — ADMIN-only create / update /
 *       activate-deactivate with code-uniqueness and type/value sanity.</li>
 * </ul>
 *
 * <p>All discount math is delegated to the pure {@link CouponCalculator}.
 */
@Service
public class CouponService {

    private final CouponRepository couponRepository;
    private final OrderRepository orderRepository;
    private final CheckoutPricing checkoutPricing;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public CouponService(CouponRepository couponRepository,
                         OrderRepository orderRepository,
                         CheckoutPricing checkoutPricing) {
        this(couponRepository, orderRepository, checkoutPricing, Clock.systemDefaultZone());
    }

    /** Test-friendly constructor allowing a fixed {@link Clock}. */
    CouponService(CouponRepository couponRepository,
                  OrderRepository orderRepository,
                  CheckoutPricing checkoutPricing,
                  Clock clock) {
        this.couponRepository = couponRepository;
        this.orderRepository = orderRepository;
        this.checkoutPricing = checkoutPricing;
        this.clock = clock;
    }

    // --- Preview (public) ---------------------------------------------------

    /**
     * Previews a coupon against the supplied cart without mutating usage. An
     * unknown code, or one that fails an applicability gate, returns an invalid
     * response with a customer-facing message; per-customer limits are not
     * checked here (no customer identity at preview time) — they are enforced
     * authoritatively at checkout.
     */
    @Transactional(readOnly = true)
    public ValidateCouponResponse validate(ValidateCouponRequest request) {
        Money subtotal = checkoutPricing.subtotal(request.items());
        String code = Coupon.normalizeCode(request.code());

        Coupon coupon = couponRepository.findByCode(code).orElse(null);
        if (coupon == null) {
            return new ValidateCouponResponse(false, code, BigDecimal.ZERO.setScale(Money.SCALE),
                    subtotal.toBigDecimal(), false, "This coupon code is not valid.");
        }

        CouponDiscount result = CouponCalculator.evaluate(coupon.toPolicy(), subtotal, now());
        if (!result.valid()) {
            return new ValidateCouponResponse(false, code, BigDecimal.ZERO.setScale(Money.SCALE),
                    subtotal.toBigDecimal(), false, result.reason());
        }

        Money newTotal = subtotal.subtract(result.discountAmount());
        String message = describeApplied(result);
        return new ValidateCouponResponse(true, code, result.discountAmount().toBigDecimal(),
                newTotal.toBigDecimal(), result.freeShipping(), message);
    }

    // --- Apply (authoritative, at checkout) ---------------------------------

    /**
     * Authoritatively applies a coupon during order creation. Re-validates the
     * coupon against the real (server-priced) {@code subtotal} and the customer's
     * prior redemptions of this code, records the redemption, and returns the
     * discount to subtract. Rejects with a {@link ValidationException} (400) when
     * the code is unknown, not applicable, or over a usage limit — including a
     * defensive re-check of the total usage limit to guard a race.
     *
     * @param rawCode        the coupon code from the checkout request
     * @param subtotal       the server-priced cart subtotal
     * @param customerMobile the customer mobile (for per-customer limit counting)
     */
    @Transactional
    public CouponApplication applyToCheckout(String rawCode, Money subtotal, String customerMobile) {
        String code = Coupon.normalizeCode(rawCode);
        Coupon coupon = couponRepository.findByCode(code)
                .orElseThrow(() -> new ValidationException("This coupon code is not valid."));

        long customerUsage = (customerMobile == null || customerMobile.isBlank())
                ? 0L
                : orderRepository.countByCouponCodeAndCustomerMobile(code, customerMobile.trim());

        CouponDiscount result =
                CouponCalculator.evaluate(coupon.toPolicy(), subtotal, now(), customerUsage);
        if (!result.valid()) {
            throw new ValidationException(result.reason());
        }

        // Defensive re-check of the total usage limit right before recording the
        // redemption, so a concurrent burst cannot exceed usage_limit.
        if (coupon.getUsageLimit() != null && coupon.getUsedCount() >= coupon.getUsageLimit()) {
            throw new ValidationException("This coupon has reached its usage limit.");
        }
        coupon.incrementUsedCount();
        couponRepository.save(coupon);

        return new CouponApplication(code, result.discountAmount(), result.freeShipping());
    }

    // --- Admin management ---------------------------------------------------

    @Transactional(readOnly = true)
    public List<CouponResponse> list() {
        return couponRepository.findAllByOrderByCreatedAtDescIdDesc().stream()
                .map(CouponResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public CouponResponse get(Long id) {
        return CouponResponse.from(require(id));
    }

    @Transactional
    public CouponResponse create(CouponRequest request) {
        String code = Coupon.normalizeCode(request.code());
        if (couponRepository.existsByCode(code)) {
            throw new DuplicateResourceException("DUPLICATE_COUPON_CODE",
                    "A coupon with code " + code + " already exists.");
        }
        BigDecimal value = sanitize(request);
        validateWindow(request);

        Coupon coupon = new Coupon(code, blankToNull(request.description()), request.type(), value,
                request.minCartAmount(), maxDiscountFor(request.type(), request.maxDiscountAmount()),
                request.active(), request.startsAt(), request.endsAt(),
                request.usageLimit(), request.perCustomerLimit());
        return CouponResponse.from(couponRepository.save(coupon));
    }

    @Transactional
    public CouponResponse update(Long id, CouponRequest request) {
        Coupon coupon = require(id);
        String code = Coupon.normalizeCode(request.code());
        couponRepository.findByCode(code)
                .filter(other -> !other.getId().equals(coupon.getId()))
                .ifPresent(other -> {
                    throw new DuplicateResourceException("DUPLICATE_COUPON_CODE",
                            "A coupon with code " + code + " already exists.");
                });
        BigDecimal value = sanitize(request);
        validateWindow(request);

        coupon.update(code, blankToNull(request.description()), request.type(), value,
                request.minCartAmount(), maxDiscountFor(request.type(), request.maxDiscountAmount()),
                request.active(), request.startsAt(), request.endsAt(),
                request.usageLimit(), request.perCustomerLimit());
        return CouponResponse.from(couponRepository.save(coupon));
    }

    /** Activates or deactivates a coupon (soft delete via the active toggle). */
    @Transactional
    public CouponResponse setActive(Long id, boolean active) {
        Coupon coupon = require(id);
        coupon.setActive(active);
        return CouponResponse.from(couponRepository.save(coupon));
    }

    // --- Helpers ------------------------------------------------------------

    private Coupon require(Long id) {
        return couponRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Coupon " + id + " does not exist."));
    }

    /**
     * Type/value sanity, returning the normalized value to persist:
     * PERCENT must be within (0, 100]; FLAT must be positive; FREE_SHIPPING
     * ignores the value (stored as 0).
     */
    private BigDecimal sanitize(CouponRequest request) {
        BigDecimal value = request.value() != null ? request.value() : BigDecimal.ZERO;
        switch (request.type()) {
            case PERCENT -> {
                if (value.signum() <= 0 || value.compareTo(BigDecimal.valueOf(100)) > 0) {
                    throw new ValidationException("A percentage coupon value must be between 0 and 100.");
                }
            }
            case FLAT -> {
                if (value.signum() <= 0) {
                    throw new ValidationException("A flat coupon value must be greater than 0.");
                }
            }
            case FREE_SHIPPING -> value = BigDecimal.ZERO;
            default -> throw new ValidationException("Unsupported coupon type.");
        }
        return value.setScale(Money.SCALE, java.math.RoundingMode.HALF_UP);
    }

    private void validateWindow(CouponRequest request) {
        if (request.startsAt() != null && request.endsAt() != null
                && request.endsAt().isBefore(request.startsAt())) {
            throw new ValidationException("The coupon end date must be after the start date.");
        }
    }

    /** The max-discount cap is only meaningful for PERCENT; cleared otherwise. */
    private BigDecimal maxDiscountFor(CouponType type, BigDecimal maxDiscountAmount) {
        return type == CouponType.PERCENT ? maxDiscountAmount : null;
    }

    private String describeApplied(CouponDiscount result) {
        if (result.freeShipping()) {
            return "Free shipping applied.";
        }
        return "Coupon applied. You save " + result.discountAmount() + ".";
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
