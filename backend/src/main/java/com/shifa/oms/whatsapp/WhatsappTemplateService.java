package com.shifa.oms.whatsapp;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.whatsapp.dto.WhatsappTemplateRequest;
import com.shifa.oms.whatsapp.dto.WhatsappTemplateResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Customizable WhatsApp message templates (V44). Managers create/edit/remove
 * templates; senders (order / customer screens) read the active ones. Reads are
 * open to any staff member who can message a customer; writes are limited to
 * ADMIN / ACCOUNTANT / TEAM_LEAD (enforced at the controller).
 */
@Service
public class WhatsappTemplateService {

    private final WhatsappTemplateRepository repository;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;

    public WhatsappTemplateService(WhatsappTemplateRepository repository,
                                   CurrentUserService currentUserService,
                                   AuditService auditService) {
        this.repository = repository;
        this.currentUserService = currentUserService;
        this.auditService = auditService;
    }

    /** The active templates shown to senders, in display order. */
    @Transactional(readOnly = true)
    public List<WhatsappTemplateResponse> listActive() {
        return repository.findByActiveTrueOrderBySortOrderAscIdAsc().stream()
                .map(WhatsappTemplateResponse::from).toList();
    }

    /** Every template (management view), in display order. */
    @Transactional(readOnly = true)
    public List<WhatsappTemplateResponse> listAll() {
        return repository.findAllByOrderBySortOrderAscIdAsc().stream()
                .map(WhatsappTemplateResponse::from).toList();
    }

    /** Creates a new template with an auto-generated unique key. */
    @Transactional
    public WhatsappTemplateResponse create(WhatsappTemplateRequest request) {
        AuthPrincipal actor = currentUserService.currentUser().orElse(null);
        Long actorId = actor != null ? actor.userId() : null;
        String actorName = actor != null ? actor.username() : null;
        String key = uniqueKey(request.title());
        WhatsappTemplate template = new WhatsappTemplate(
                key,
                request.title().trim(),
                request.body().trim(),
                request.icon(),
                request.sortOrder() == null ? 0 : request.sortOrder(),
                actorId,
                actorName);
        if (request.active() != null) {
            template.setActive(request.active());
        }
        WhatsappTemplate saved = repository.save(template);
        auditService.record(AuditActions.WHATSAPP_TEMPLATE_CREATED, AuditActions.ENTITY_WHATSAPP_TEMPLATE,
                String.valueOf(saved.getId()), "Created WhatsApp template " + saved.getTitle());
        return WhatsappTemplateResponse.from(saved);
    }

    /** Updates an existing template's editable fields (key is immutable). */
    @Transactional
    public WhatsappTemplateResponse update(Long id, WhatsappTemplateRequest request) {
        WhatsappTemplate template = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("WhatsApp template " + id + " not found."));
        template.setTitle(request.title().trim());
        template.setBody(request.body().trim());
        template.setIcon(request.icon());
        if (request.active() != null) {
            template.setActive(request.active());
        }
        if (request.sortOrder() != null) {
            template.setSortOrder(request.sortOrder());
        }
        template.touch();
        WhatsappTemplate saved = repository.save(template);
        auditService.record(AuditActions.WHATSAPP_TEMPLATE_UPDATED, AuditActions.ENTITY_WHATSAPP_TEMPLATE,
                String.valueOf(id), "Updated WhatsApp template " + saved.getTitle());
        return WhatsappTemplateResponse.from(saved);
    }

    /** Permanently removes a template. */
    @Transactional
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new ResourceNotFoundException("WhatsApp template " + id + " not found.");
        }
        repository.deleteById(id);
        auditService.record(AuditActions.WHATSAPP_TEMPLATE_DELETED, AuditActions.ENTITY_WHATSAPP_TEMPLATE,
                String.valueOf(id), "Deleted WhatsApp template");
    }

    /**
     * Builds a URL-safe slug key from the title and ensures it is unique by
     * appending a numeric suffix if needed. Falls back to "template" for a
     * title that has no alphanumeric characters.
     */
    private String uniqueKey(String title) {
        String base = (title == null ? "" : title).toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        if (base.isBlank()) {
            base = "template";
        }
        if (base.length() > 50) {
            base = base.substring(0, 50).replaceAll("(-+$)", "");
        }
        String candidate = base;
        int n = 2;
        while (repository.existsByTemplateKey(candidate)) {
            candidate = base + "-" + n;
            n++;
        }
        return candidate;
    }
}
