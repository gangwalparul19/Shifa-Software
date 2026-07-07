package com.shifa.oms.coupon;

import com.shifa.oms.common.DuplicateResourceException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.coupon.domain.CouponType;
import com.shifa.oms.coupon.dto.CouponRequest;
import com.shifa.oms.coupon.dto.CouponResponse;
import com.shifa.oms.coupon.dto.ValidateCouponRequest;
import com.shifa.oms.coupon.dto.ValidateCouponResponse;
import com.shifa.oms.order.CheckoutPricing;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.domain.Money;
import com.shifa.oms.order.dto.CheckoutRequest.CheckoutItemRequest;
import com.shifa.oms.product.Product;
import com.shifa.oms.product.ProductRepository;
import com.shifa.oms.product.ProductVisibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Example-based unit tests for {@link CouponService} (Phase D). Repositories and
 * cart pricing are mocked so these run without a database. Covers the public
 * validate/preview path (pricing + discount + new total), authoritative
 * checkout redemption (reduces total, increments usage, rejects over-limit), and
 * admin CRUD with code-uniqueness + type/value sanity.
 */
@ExtendWith(MockitoExtension.class)
class CouponServiceTest {

    @Mock
    private CouponRepository couponRepository;
    @Mock
    private OrderRepository orderRepository;
    // CheckoutPricing is a concrete class (not mockable on this JVM); use a real
    // instance over a mocked ProductRepository so preview pricing is exercised.
    @Mock
    private ProductRepository productRepository;

    private CouponService service;

    private final Clock clock = Clock.fixed(Instant.parse("2025-06-15T12:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        service = new CouponService(couponRepository, orderRepository,
                new CheckoutPricing(productRepository), clock);
        lenient().when(couponRepository.save(any(Coupon.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /** Stubs product 1 at the given sale price so a single-unit cart prices to it. */
    private void cartPricesTo(String salePrice) {
        Product p = new Product("SKU-1", "Product 1", "d",
                new BigDecimal("999.00"), new BigDecimal(salePrice), ProductVisibility.PUBLISHED);
        when(productRepository.findById(1L)).thenReturn(Optional.of(p));
    }

    private Coupon percentCoupon(String code, String value) {
        return new Coupon(code, "desc", CouponType.PERCENT, new BigDecimal(value),
                null, null, true, null, null, null, null);
    }

    private ValidateCouponRequest validateRequest(String code) {
        return new ValidateCouponRequest(code, List.of(new CheckoutItemRequest(1L, 1)));
    }

    // --- validate (preview) -------------------------------------------------

    @Test
    void validateUnknownCodeReturnsInvalid() {
        cartPricesTo("500.00");
        when(couponRepository.findByCode("NOPE")).thenReturn(Optional.empty());

        ValidateCouponResponse response = service.validate(validateRequest("nope"));

        assertThat(response.valid()).isFalse();
        assertThat(response.code()).isEqualTo("NOPE");
        assertThat(response.discountAmount()).isEqualByComparingTo("0.00");
        assertThat(response.newTotal()).isEqualByComparingTo("500.00");
    }

    @Test
    void validateValidPercentReturnsDiscountAndNewTotal() {
        cartPricesTo("500.00");
        when(couponRepository.findByCode("SAVE10")).thenReturn(Optional.of(percentCoupon("SAVE10", "10.00")));

        ValidateCouponResponse response = service.validate(validateRequest("save10"));

        assertThat(response.valid()).isTrue();
        assertThat(response.discountAmount()).isEqualByComparingTo("50.00");
        assertThat(response.newTotal()).isEqualByComparingTo("450.00");
    }

    @Test
    void validateMinCartNotMetReturnsInvalidWithMessage() {
        Coupon coupon = new Coupon("BIG", null, CouponType.FLAT, new BigDecimal("100.00"),
                new BigDecimal("1000.00"), null, true, null, null, null, null);
        cartPricesTo("500.00");
        when(couponRepository.findByCode("BIG")).thenReturn(Optional.of(coupon));

        ValidateCouponResponse response = service.validate(validateRequest("big"));

        assertThat(response.valid()).isFalse();
        assertThat(response.message()).contains("minimum cart amount");
    }

    // --- applyToCheckout (authoritative) -----------------------------------

    @Test
    void applyToCheckoutReducesTotalAndIncrementsUsage() {
        Coupon coupon = percentCoupon("SAVE10", "10.00");
        when(couponRepository.findByCode("SAVE10")).thenReturn(Optional.of(coupon));
        when(orderRepository.countByCouponCodeAndCustomerMobile("SAVE10", "9812345678")).thenReturn(0L);

        CouponApplication application =
                service.applyToCheckout("save10", Money.of("500.00"), "9812345678");

        assertThat(application.couponCode()).isEqualTo("SAVE10");
        assertThat(application.discountAmount()).isEqualTo(Money.of("50.00"));
        assertThat(coupon.getUsedCount()).isEqualTo(1);
        verify(couponRepository).save(coupon);
    }

    @Test
    void applyToCheckoutRejectsUnknownCode() {
        when(couponRepository.findByCode("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.applyToCheckout("nope", Money.of("500.00"), "9812345678"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("not valid");
    }

    @Test
    void applyToCheckoutRejectsWhenUsageLimitReached() {
        Coupon coupon = new Coupon("ONCE", null, CouponType.FLAT, new BigDecimal("50.00"),
                null, null, true, null, null, 1, null);
        coupon.incrementUsedCount(); // used_count = 1, usage_limit = 1 -> exhausted
        when(couponRepository.findByCode("ONCE")).thenReturn(Optional.of(coupon));

        assertThatThrownBy(() -> service.applyToCheckout("once", Money.of("500.00"), "9812345678"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("usage limit");
        verify(couponRepository, never()).save(any());
    }

    @Test
    void applyToCheckoutRejectsWhenPerCustomerLimitReached() {
        Coupon coupon = new Coupon("ONEPER", null, CouponType.FLAT, new BigDecimal("50.00"),
                null, null, true, null, null, null, 1);
        when(couponRepository.findByCode("ONEPER")).thenReturn(Optional.of(coupon));
        when(orderRepository.countByCouponCodeAndCustomerMobile("ONEPER", "9812345678")).thenReturn(1L);

        assertThatThrownBy(() -> service.applyToCheckout("oneper", Money.of("500.00"), "9812345678"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("already used");
    }

    // --- admin CRUD ---------------------------------------------------------

    @Test
    void createRejectsDuplicateCode() {
        when(couponRepository.existsByCode("SAVE10")).thenReturn(true);
        CouponRequest request = new CouponRequest("save10", "d", CouponType.PERCENT,
                new BigDecimal("10.00"), null, null, true, null, null, null, null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(DuplicateResourceException.class);
        verify(couponRepository, never()).save(any());
    }

    @Test
    void createRejectsPercentValueOverHundred() {
        when(couponRepository.existsByCode(anyString())).thenReturn(false);
        CouponRequest request = new CouponRequest("BIG", "d", CouponType.PERCENT,
                new BigDecimal("150.00"), null, null, true, null, null, null, null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("between 0 and 100");
    }

    @Test
    void createStoresUpperCasedCode() {
        when(couponRepository.existsByCode("SAVE10")).thenReturn(false);
        CouponRequest request = new CouponRequest("save10", "10% off", CouponType.PERCENT,
                new BigDecimal("10.00"), new BigDecimal("200.00"), new BigDecimal("100.00"),
                true, null, null, 100, 1);

        CouponResponse response = service.create(request);

        assertThat(response.code()).isEqualTo("SAVE10");
        assertThat(response.type()).isEqualTo(CouponType.PERCENT);
        assertThat(response.value()).isEqualByComparingTo("10.00");
        assertThat(response.maxDiscountAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    void updateRejectsCodeCollisionWithAnotherCoupon() {
        Coupon existing = percentCoupon("SAVE10", "10.00");
        org.springframework.test.util.ReflectionTestUtils.setField(existing, "id", 1L);
        Coupon other = percentCoupon("SAVE20", "20.00");
        org.springframework.test.util.ReflectionTestUtils.setField(other, "id", 2L);

        when(couponRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(couponRepository.findByCode("SAVE20")).thenReturn(Optional.of(other));

        CouponRequest request = new CouponRequest("save20", "d", CouponType.PERCENT,
                new BigDecimal("10.00"), null, null, true, null, null, null, null);

        assertThatThrownBy(() -> service.update(1L, request))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void setActiveTogglesFlag() {
        Coupon coupon = percentCoupon("SAVE10", "10.00");
        when(couponRepository.findById(1L)).thenReturn(Optional.of(coupon));

        CouponResponse response = service.setActive(1L, false);

        assertThat(response.active()).isFalse();
    }
}
