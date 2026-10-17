package com.shifa.oms.performance;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the pure target-attainment math + week anchoring (CRM
 * expansion Phase 2, Task 13; Req 12.2), covering both week and month targets
 * through the shared {@link SalesTargetService#attainmentPct} /
 * {@link SalesTargetService#weekAnchor} helpers.
 */
class SalesTargetAttainmentTest {

    @Test
    void attainmentIsAchievedOverTargetAsPercent() {
        assertThat(SalesTargetService.attainmentPct(new BigDecimal("500"), new BigDecimal("1000")))
                .isEqualTo(50.0);
        assertThat(SalesTargetService.attainmentPct(new BigDecimal("1500"), new BigDecimal("1000")))
                .isEqualTo(150.0);
    }

    @Test
    void attainmentIsNullWhenNoPositiveTarget() {
        assertThat(SalesTargetService.attainmentPct(new BigDecimal("500"), null)).isNull();
        assertThat(SalesTargetService.attainmentPct(new BigDecimal("500"), BigDecimal.ZERO)).isNull();
    }

    @Test
    void attainmentTreatsNullAchievedAsZero() {
        assertThat(SalesTargetService.attainmentPct(null, new BigDecimal("1000"))).isEqualTo(0.0);
    }

    @Test
    void weekAnchorIsTheMondayOfTheIsoWeek() {
        // 2026-02-11 is a Wednesday → Monday is 2026-02-09.
        LocalDate wed = LocalDate.of(2026, 2, 11);
        LocalDate monday = SalesTargetService.weekAnchor(wed);
        assertThat(monday).isEqualTo(LocalDate.of(2026, 2, 9));
        assertThat(monday.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        // A Monday maps to itself.
        assertThat(SalesTargetService.weekAnchor(monday)).isEqualTo(monday);
    }
}
