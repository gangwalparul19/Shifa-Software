package com.shifa.oms.crm;

import com.shifa.oms.crm.dto.AddNoteRequest;
import com.shifa.oms.crm.dto.AddTagRequest;
import com.shifa.oms.crm.dto.CustomerNoteResponse;
import com.shifa.oms.crm.dto.CustomerProfileResponse;
import com.shifa.oms.crm.dto.CustomerRiskResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Customer "360" CRM-depth endpoints (FEATURE-ROADMAP §1): the enriched profile,
 * the delivery-reliability risk lookup, and staff-managed tags/notes.
 *
 * <p>Shares the {@code /api/admin/customers} base path with the existing
 * {@link CustomerController} (list/detail) but is a separate controller so that
 * one — and its tests — is untouched. Access is the same CRM audience
 * ({@code ADMIN}/{@code ACCOUNTANT}/{@code SALESPERSON}); a {@code SALESPERSON}
 * is scoped by {@link CustomerInsightService} to customers derived from their own
 * orders.
 */
@RestController
@RequestMapping("/api/admin/customers")
@PreAuthorize("hasAnyRole('ADMIN', 'ACCOUNTANT', 'SALESPERSON')")
public class CustomerCrmController {

    private final CustomerInsightService insightService;

    public CustomerCrmController(CustomerInsightService insightService) {
        this.insightService = insightService;
    }

    /** The full Customer 360 profile (summary + metrics + risk + products + tags + notes + history). */
    @GetMapping("/{mobile}/profile")
    public CustomerProfileResponse profile(@PathVariable String mobile) {
        return insightService.profile(mobile);
    }

    /**
     * A customer's delivery-reliability risk — used by the New Order form to nudge
     * toward prepaid. Never 404s: an unknown mobile is treated as a new customer.
     */
    @GetMapping("/{mobile}/risk")
    public CustomerRiskResponse risk(@PathVariable String mobile) {
        return insightService.risk(mobile);
    }

    /** Adds a note to the customer's timeline; returns the timeline (newest first). */
    @PostMapping("/{mobile}/notes")
    public List<CustomerNoteResponse> addNote(@PathVariable String mobile,
                                              @Valid @RequestBody AddNoteRequest request) {
        return insightService.addNote(mobile, request.note());
    }

    /** Attaches a segment tag to the customer; returns the updated tag list. */
    @PostMapping("/{mobile}/tags")
    public List<String> addTag(@PathVariable String mobile,
                               @Valid @RequestBody AddTagRequest request) {
        return insightService.addTag(mobile, request.tag());
    }

    /** Removes a segment tag from the customer; returns the updated tag list. */
    @DeleteMapping("/{mobile}/tags/{tag}")
    public List<String> removeTag(@PathVariable String mobile, @PathVariable String tag) {
        return insightService.removeTag(mobile, tag);
    }
}
