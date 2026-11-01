package com.shifa.oms.incentive;

import com.shifa.oms.incentive.dto.IncentiveRules.RulesRequest;
import com.shifa.oms.incentive.dto.IncentiveRules.RulesResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN-only management of the daily incentive slabs (Settings → Incentives). */
@RestController
@RequestMapping("/api/admin/settings/incentive-rules")
@PreAuthorize("hasRole('ADMIN')")
public class IncentiveRuleController {

    private final IncentiveRuleService service;

    public IncentiveRuleController(IncentiveRuleService service) {
        this.service = service;
    }

    /** The rules in force today and every earlier or scheduled version. */
    @GetMapping
    public RulesResponse rules() {
        return service.rules();
    }

    /** Saves a new version of the rules; earlier versions are kept as the audit trail. */
    @PostMapping
    public RulesResponse save(@Valid @RequestBody RulesRequest request) {
        return service.save(request);
    }
}
