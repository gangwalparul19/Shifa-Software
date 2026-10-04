package com.shifa.oms.dashboard;

import com.shifa.oms.dashboard.dto.OwnerSnapshotResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The owner's one-screen snapshot ({@code GET /api/admin/dashboard/owner-snapshot},
 * ADMIN only): today's trading plus the actionable backlog (approvals, payments,
 * failed deliveries, COD to collect + over-SLA, pending claims, stuck shipments)
 * and today's top salesperson. Backs the admin dashboard owner-overview strip
 * (ENHANCEMENT 1.2); the same figures feed the daily owner email (1.1).
 */
@RestController
@RequestMapping("/api/admin/dashboard")
@PreAuthorize("hasRole('ADMIN')")
public class OwnerSnapshotController {

    private final OwnerSnapshotService service;

    public OwnerSnapshotController(OwnerSnapshotService service) {
        this.service = service;
    }

    @GetMapping("/owner-snapshot")
    public OwnerSnapshotResponse ownerSnapshot() {
        return service.snapshot();
    }
}
