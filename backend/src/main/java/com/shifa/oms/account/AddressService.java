package com.shifa.oms.account;

import com.shifa.oms.account.dto.AddressRequest;
import com.shifa.oms.account.dto.AddressResponse;
import com.shifa.oms.common.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Manages a customer's saved address book. Every operation is scoped to the
 * calling customer's {@code userId} so a customer can only ever read or modify
 * their own addresses (a lookup for an id owned by another user yields 404, not
 * a leak or a cross-user mutation).
 *
 * <p>At most one address per user is the default: setting a new default clears
 * the flag on the customer's other addresses within the same transaction. The
 * first address a customer saves becomes the default automatically.
 */
@Service
public class AddressService {

    private final CustomerAddressRepository addressRepository;

    public AddressService(CustomerAddressRepository addressRepository) {
        this.addressRepository = addressRepository;
    }

    @Transactional(readOnly = true)
    public List<AddressResponse> list(Long userId) {
        return addressRepository.findByUserIdOrderByIsDefaultDescCreatedAtDesc(userId).stream()
                .map(AddressResponse::from)
                .toList();
    }

    @Transactional
    public AddressResponse create(Long userId, AddressRequest request) {
        boolean first = addressRepository.findByUserId(userId).isEmpty();
        boolean makeDefault = request.makeDefault() || first;

        CustomerAddress address = new CustomerAddress(
                userId,
                blankToNull(request.label()),
                request.fullName().trim(),
                request.mobile().trim(),
                request.addressLine().trim(),
                request.city().trim(),
                request.state().trim(),
                request.postalCode().trim(),
                makeDefault);

        if (makeDefault) {
            clearDefaults(userId);
        }
        return AddressResponse.from(addressRepository.save(address));
    }

    @Transactional
    public AddressResponse update(Long userId, Long addressId, AddressRequest request) {
        CustomerAddress address = requireOwned(userId, addressId);
        address.setLabel(blankToNull(request.label()));
        address.setFullName(request.fullName().trim());
        address.setMobile(request.mobile().trim());
        address.setAddressLine(request.addressLine().trim());
        address.setCity(request.city().trim());
        address.setState(request.state().trim());
        address.setPostalCode(request.postalCode().trim());

        if (request.makeDefault() && !address.isDefault()) {
            clearDefaults(userId);
            address.setDefault(true);
        }
        return AddressResponse.from(addressRepository.save(address));
    }

    @Transactional
    public void delete(Long userId, Long addressId) {
        CustomerAddress address = requireOwned(userId, addressId);
        boolean wasDefault = address.isDefault();
        addressRepository.delete(address);
        // Promote another address to default so the customer always has one.
        if (wasDefault) {
            addressRepository.findByUserIdOrderByIsDefaultDescCreatedAtDesc(userId).stream()
                    .findFirst()
                    .ifPresent(next -> {
                        next.setDefault(true);
                        addressRepository.save(next);
                    });
        }
    }

    @Transactional
    public AddressResponse setDefault(Long userId, Long addressId) {
        CustomerAddress address = requireOwned(userId, addressId);
        clearDefaults(userId);
        address.setDefault(true);
        return AddressResponse.from(addressRepository.save(address));
    }

    private CustomerAddress requireOwned(Long userId, Long addressId) {
        return addressRepository.findByIdAndUserId(addressId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Address " + addressId + " does not exist."));
    }

    private void clearDefaults(Long userId) {
        for (CustomerAddress existing : addressRepository.findByUserId(userId)) {
            if (existing.isDefault()) {
                existing.setDefault(false);
                addressRepository.save(existing);
            }
        }
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
