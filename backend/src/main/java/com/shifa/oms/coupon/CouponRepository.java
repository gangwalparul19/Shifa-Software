package com.shifa.oms.coupon;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link Coupon}.
 *
 * <p>Codes are stored upper-cased, so lookups pass an already-normalized code
 * ({@link Coupon#normalizeCode(String)}). The admin list is ordered newest
 * first for the management grid.
 */
public interface CouponRepository extends JpaRepository<Coupon, Long> {

    /** The coupon with this (upper-cased) code, if any. */
    Optional<Coupon> findByCode(String code);

    /** Whether a coupon already uses this (upper-cased) code (uniqueness check). */
    boolean existsByCode(String code);

    /** All coupons, newest first, for the admin management grid. */
    List<Coupon> findAllByOrderByCreatedAtDescIdDesc();
}
