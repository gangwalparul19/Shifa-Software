package com.shifa.oms.lead;

import com.shifa.oms.lead.dto.LeadReports.ByStageReport;
import com.shifa.oms.lead.dto.LeadReports.GroupPerformanceReport;
import com.shifa.oms.order.LeadSource;
import com.shifa.oms.reporting.domain.DateRange;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the pure by-stage / by-user / by-team lead report groupings
 * (CRM expansion Phase 2, Task 14; Req 13.1, 13.2).
 */
class LeadGroupReportTest {

    private final LeadReportAggregator agg = new LeadReportAggregator();
    private final DateRange all = DateRange.of(null, null);

    private static LeadReportRecord rec(LeadStatus status, Long owner, BigDecimal revenue) {
        return new LeadReportRecord(LeadSource.WHATSAPP, status, null, owner,
                LocalDate.of(2026, 2, 1), status == LeadStatus.WON ? 1L : null, revenue);
    }

    @Test
    void byStageCountsEveryStageIncludingTerminal() {
        List<LeadReportRecord> leads = List.of(
                rec(LeadStatus.NEW, 1L, null),
                rec(LeadStatus.NEW, 2L, null),
                rec(LeadStatus.WON, 1L, new BigDecimal("1000")),
                rec(LeadStatus.LOST, 2L, null));

        ByStageReport report = agg.byStage(leads, all);

        assertThat(report.total()).isEqualTo(4);
        assertThat(report.rows()).extracting(r -> r.stage())
                .contains(LeadStatus.NEW, LeadStatus.WON, LeadStatus.LOST);
        assertThat(report.rows().stream().filter(r -> r.stage() == LeadStatus.NEW).findFirst()
                .orElseThrow().count()).isEqualTo(2);
    }

    @Test
    void byUserComputesConversionAndConvertedRevenue() {
        List<LeadReportRecord> leads = List.of(
                rec(LeadStatus.NEW, 10L, null),
                rec(LeadStatus.WON, 10L, new BigDecimal("500")),
                rec(LeadStatus.WON, 10L, new BigDecimal("250")),
                rec(LeadStatus.NEW, 20L, null));

        GroupPerformanceReport report = agg.byUser(leads, all, Map.of("10", "Asha", "20", "Bala"));

        assertThat(report.total()).isEqualTo(4);
        var asha = report.rows().stream().filter(r -> r.key().equals("10")).findFirst().orElseThrow();
        assertThat(asha.label()).isEqualTo("Asha");
        assertThat(asha.leads()).isEqualTo(3);
        assertThat(asha.won()).isEqualTo(2);
        assertThat(asha.convertedRevenue()).isEqualByComparingTo("750");
        // conversion rate = 2/3 = 0.6667
        assertThat(asha.conversionRate()).isEqualByComparingTo("0.6667");
    }

    @Test
    void byTeamGroupsOwnersViaTheOwnerToTeamMap() {
        List<LeadReportRecord> leads = List.of(
                rec(LeadStatus.WON, 10L, new BigDecimal("1000")),
                rec(LeadStatus.NEW, 11L, null),
                rec(LeadStatus.NEW, 20L, null));
        // owners 10 & 11 belong to team lead 5; owner 20 has no team.
        Map<Long, String> ownerToTeam = Map.of(10L, "5", 11L, "5");

        GroupPerformanceReport report = agg.byTeam(leads, all, ownerToTeam, Map.of("5", "Team A"));

        var teamA = report.rows().stream().filter(r -> r.key().equals("5")).findFirst().orElseThrow();
        assertThat(teamA.label()).isEqualTo("Team A");
        assertThat(teamA.leads()).isEqualTo(2);
        assertThat(teamA.won()).isEqualTo(1);
        assertThat(teamA.convertedRevenue()).isEqualByComparingTo("1000");
        // owner 20 falls under UNSPECIFIED
        assertThat(report.rows()).anyMatch(r -> r.key().equals(LeadReportAggregator.UNSPECIFIED_OWNER));
    }
}
