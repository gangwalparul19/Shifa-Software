package com.shifa.oms.announcement;

import com.shifa.oms.announcement.dto.AnnouncementResponse;
import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.common.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Staff announcement banners (FEATURE-ROADMAP §8.4): the admin posts notices that
 * every signed-in staff member sees. Reads are open to all staff (active only);
 * writes are admin-only (enforced at the controller).
 */
@Service
public class AnnouncementService {

    private final AnnouncementRepository repository;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;

    public AnnouncementService(AnnouncementRepository repository,
                               CurrentUserService currentUserService,
                               AuditService auditService) {
        this.repository = repository;
        this.currentUserService = currentUserService;
        this.auditService = auditService;
    }

    /** The active announcements shown to staff, newest first. */
    @Transactional(readOnly = true)
    public List<AnnouncementResponse> listActive() {
        return repository.findByActiveTrueOrderByCreatedAtDesc().stream()
                .map(AnnouncementResponse::from).toList();
    }

    /** Every announcement (admin management view), newest first. */
    @Transactional(readOnly = true)
    public List<AnnouncementResponse> listAll() {
        return repository.findAllByOrderByCreatedAtDesc().stream()
                .map(AnnouncementResponse::from).toList();
    }

    /** Posts a new (active) announcement. */
    @Transactional
    public AnnouncementResponse create(String message, String severity) {
        AuthPrincipal actor = currentUserService.currentUser().orElse(null);
        Long actorId = actor != null ? actor.userId() : null;
        String actorName = actor != null ? actor.username() : null;
        String normalizedSeverity = (severity == null || severity.isBlank())
                ? Announcement.SEVERITY_INFO : severity;
        Announcement saved = repository.save(
                new Announcement(message.trim(), normalizedSeverity, actorId, actorName));
        auditService.record(AuditActions.ANNOUNCEMENT_CREATED, AuditActions.ENTITY_ANNOUNCEMENT,
                String.valueOf(saved.getId()), "Posted announcement");
        return AnnouncementResponse.from(saved);
    }

    /** Activates or deactivates an announcement. */
    @Transactional
    public AnnouncementResponse setActive(Long id, boolean active) {
        Announcement announcement = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Announcement " + id + " not found."));
        announcement.setActive(active);
        Announcement saved = repository.save(announcement);
        auditService.record(AuditActions.ANNOUNCEMENT_UPDATED, AuditActions.ENTITY_ANNOUNCEMENT,
                String.valueOf(id), active ? "Activated announcement" : "Deactivated announcement");
        return AnnouncementResponse.from(saved);
    }

    /** Permanently removes an announcement. */
    @Transactional
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new ResourceNotFoundException("Announcement " + id + " not found.");
        }
        repository.deleteById(id);
        auditService.record(AuditActions.ANNOUNCEMENT_DELETED, AuditActions.ENTITY_ANNOUNCEMENT,
                String.valueOf(id), "Deleted announcement");
    }
}
