package com.shifa.oms.announcement;

import com.shifa.oms.announcement.dto.AnnouncementResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Staff-facing announcement feed (FEATURE-ROADMAP §8.4). Any authenticated staff
 * member reads the active announcements to render the app-wide banner.
 */
@RestController
@RequestMapping("/api/announcements")
@PreAuthorize("isAuthenticated()")
public class StaffAnnouncementController {

    private final AnnouncementService service;

    public StaffAnnouncementController(AnnouncementService service) {
        this.service = service;
    }

    /** The active announcements shown to staff (newest first). */
    @GetMapping
    public List<AnnouncementResponse> active() {
        return service.listActive();
    }
}
