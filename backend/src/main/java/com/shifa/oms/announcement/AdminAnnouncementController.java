package com.shifa.oms.announcement;

import com.shifa.oms.announcement.dto.AnnouncementResponse;
import com.shifa.oms.announcement.dto.CreateAnnouncementRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin management of staff announcement banners (FEATURE-ROADMAP §8.4):
 * post a notice, activate/deactivate it, or delete it. ADMIN only.
 */
@RestController
@RequestMapping("/api/admin/announcements")
@PreAuthorize("hasRole('ADMIN')")
public class AdminAnnouncementController {

    private final AnnouncementService service;

    public AdminAnnouncementController(AnnouncementService service) {
        this.service = service;
    }

    /** Every announcement (active and inactive), newest first. */
    @GetMapping
    public List<AnnouncementResponse> list() {
        return service.listAll();
    }

    /** Posts a new (active) announcement. */
    @PostMapping
    public AnnouncementResponse create(@Valid @RequestBody CreateAnnouncementRequest request) {
        return service.create(request.message(), request.severity());
    }

    /** Activates or deactivates an announcement ({@code ?value=true|false}). */
    @PostMapping("/{id}/active")
    public AnnouncementResponse setActive(@PathVariable Long id,
                                          @RequestParam(defaultValue = "true") boolean value) {
        return service.setActive(id, value);
    }

    /** Permanently deletes an announcement. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
