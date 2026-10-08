package com.shifa.oms.whatsapp;

import com.shifa.oms.whatsapp.dto.WhatsappTemplateRequest;
import com.shifa.oms.whatsapp.dto.WhatsappTemplateResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Customizable WhatsApp message templates (V44).
 *
 * <ul>
 *   <li>{@code GET /api/whatsapp-templates} &mdash; the active templates offered
 *       as one-tap quick messages; readable by any staff member who can message a
 *       customer (SALESPERSON / ADMIN / ACCOUNTANT / TEAM_LEAD).</li>
 *   <li>{@code GET /api/whatsapp-templates/all} &mdash; every template for the
 *       management screen (ADMIN / ACCOUNTANT / TEAM_LEAD).</li>
 *   <li>{@code POST/PUT/DELETE} &mdash; create / edit / remove a template
 *       (ADMIN / ACCOUNTANT / TEAM_LEAD).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/whatsapp-templates")
public class WhatsappTemplateController {

    private static final String CAN_READ =
            "hasAnyRole('SALESPERSON','ADMIN','ACCOUNTANT','TEAM_LEAD')";
    private static final String CAN_MANAGE =
            "hasAnyRole('ADMIN','ACCOUNTANT','TEAM_LEAD')";

    private final WhatsappTemplateService service;

    public WhatsappTemplateController(WhatsappTemplateService service) {
        this.service = service;
    }

    /** The active templates offered as one-tap quick messages. */
    @GetMapping
    @PreAuthorize(CAN_READ)
    public List<WhatsappTemplateResponse> listActive() {
        return service.listActive();
    }

    /** Every template (management view), active and inactive. */
    @GetMapping("/all")
    @PreAuthorize(CAN_MANAGE)
    public List<WhatsappTemplateResponse> listAll() {
        return service.listAll();
    }

    /** Creates a new template. */
    @PostMapping
    @PreAuthorize(CAN_MANAGE)
    public WhatsappTemplateResponse create(@Valid @RequestBody WhatsappTemplateRequest request) {
        return service.create(request);
    }

    /** Updates an existing template. */
    @PutMapping("/{id}")
    @PreAuthorize(CAN_MANAGE)
    public WhatsappTemplateResponse update(@PathVariable Long id,
                                           @Valid @RequestBody WhatsappTemplateRequest request) {
        return service.update(id, request);
    }

    /** Permanently deletes a template. */
    @DeleteMapping("/{id}")
    @PreAuthorize(CAN_MANAGE)
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
