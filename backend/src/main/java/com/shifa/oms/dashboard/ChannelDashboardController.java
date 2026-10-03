package com.shifa.oms.dashboard;

import com.shifa.oms.common.ApiException;
import com.shifa.oms.dashboard.dto.ChannelDashboardResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * The channel-aware admin dashboard (ADMIN only):
 * {@code GET /api/admin/dashboard/channel?channel=ALL|PORTAL|SHOPIFY&period=&bucket=&from=&to=}.
 * Period / bucket parsing matches {@code /api/admin/metrics}.
 */
@RestController
@RequestMapping("/api/admin/dashboard")
@PreAuthorize("hasRole('ADMIN')")
public class ChannelDashboardController {

    private final ChannelDashboardService service;

    public ChannelDashboardController(ChannelDashboardService service) {
        this.service = service;
    }

    @GetMapping("/channel")
    public ChannelDashboardResponse channel(
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String bucket,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.dashboard(parseChannel(channel), parsePeriod(period), parseBucket(bucket), from, to);
    }

    private static DashboardChannel parseChannel(String raw) {
        try {
            return DashboardChannel.from(raw);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_CHANNEL", "Unknown channel: " + raw);
        }
    }

    private static MetricsPeriod parsePeriod(String raw) {
        try {
            return MetricsPeriod.from(raw);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_PERIOD", e.getMessage());
        }
    }

    private static SalesBucket parseBucket(String raw) {
        try {
            return SalesBucket.from(raw);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_BUCKET", e.getMessage());
        }
    }
}
