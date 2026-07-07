package com.shifa.oms.account;

import com.shifa.oms.account.dto.AddressRequest;
import com.shifa.oms.account.dto.AddressResponse;
import com.shifa.oms.auth.CurrentUserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Customer address-book endpoints ({@code /api/account/addresses}). All routes
 * require an authenticated {@code CUSTOMER} and operate strictly on the calling
 * customer's own addresses (scoped by their user id in {@link AddressService}).
 */
@RestController
@RequestMapping("/api/account/addresses")
@PreAuthorize("hasRole('CUSTOMER')")
public class AccountAddressController {

    private final AddressService addressService;
    private final CurrentUserService currentUserService;

    public AccountAddressController(AddressService addressService, CurrentUserService currentUserService) {
        this.addressService = addressService;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public List<AddressResponse> list() {
        return addressService.list(currentUserId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AddressResponse create(@Valid @RequestBody AddressRequest request) {
        return addressService.create(currentUserId(), request);
    }

    @PutMapping("/{id}")
    public AddressResponse update(@PathVariable Long id, @Valid @RequestBody AddressRequest request) {
        return addressService.update(currentUserId(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        addressService.delete(currentUserId(), id);
    }

    @PostMapping("/{id}/default")
    public AddressResponse setDefault(@PathVariable Long id) {
        return addressService.setDefault(currentUserId(), id);
    }

    private Long currentUserId() {
        return currentUserService.requireCurrentUser().userId();
    }
}
