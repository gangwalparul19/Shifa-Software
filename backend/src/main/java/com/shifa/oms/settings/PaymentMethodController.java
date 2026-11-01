package com.shifa.oms.settings;

import com.shifa.oms.settings.dto.PaymentMethodRequest;
import com.shifa.oms.settings.dto.PaymentMethodResponse;
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

/** ADMIN-only payment-method management under Settings. */
@RestController
@RequestMapping("/api/admin/settings/payment-methods")
@PreAuthorize("hasRole('ADMIN')")
public class PaymentMethodController {

    private final PaymentMethodService service;

    public PaymentMethodController(PaymentMethodService service) {
        this.service = service;
    }

    @GetMapping
    public List<PaymentMethodResponse> list() { return service.listAll(); }

    @PostMapping
    public PaymentMethodResponse create(@Valid @RequestBody PaymentMethodRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    public PaymentMethodResponse update(@PathVariable Long id, @Valid @RequestBody PaymentMethodRequest request) {
        return service.update(id, request);
    }

    @PostMapping("/{id}/default")
    public PaymentMethodResponse makeDefault(@PathVariable Long id) { return service.makeDefault(id); }

    /** Deletes a never-used method; a method with payments must be deactivated instead. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
