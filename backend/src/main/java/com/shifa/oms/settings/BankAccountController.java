package com.shifa.oms.settings;

import com.shifa.oms.settings.dto.BankAccountRequest;
import com.shifa.oms.settings.dto.BankAccountResponse;
import jakarta.validation.Valid;
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

/** ADMIN-only bank/UPI account master management under Settings. */
@RestController
@RequestMapping("/api/admin/settings/bank-accounts")
@PreAuthorize("hasRole('ADMIN')")
public class BankAccountController {

    private final BankAccountService service;

    public BankAccountController(BankAccountService service) {
        this.service = service;
    }

    @GetMapping
    public List<BankAccountResponse> list() { return service.listAll(); }

    @PostMapping
    public BankAccountResponse create(@Valid @RequestBody BankAccountRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    public BankAccountResponse update(@PathVariable Long id, @Valid @RequestBody BankAccountRequest request) {
        return service.update(id, request);
    }

    /** Deactivation is used instead of deleting historical account masters. */
    @DeleteMapping("/{id}")
    public BankAccountResponse deactivate(@PathVariable Long id) { return service.deactivate(id); }
}
