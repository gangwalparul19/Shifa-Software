package com.shifa.oms.account;

import com.shifa.oms.account.dto.AddressRequest;
import com.shifa.oms.account.dto.AddressResponse;
import com.shifa.oms.common.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AddressService} with a mocked repository (no DB). Covers
 * user-scoping (a customer cannot read/update/delete another customer's
 * address), the single-default invariant, and first-address auto-default.
 */
@ExtendWith(MockitoExtension.class)
class AddressServiceTest {

    private static final Long ALICE = 1L;
    private static final Long BOB = 2L;

    @Mock
    private CustomerAddressRepository addressRepository;

    private AddressService service;

    @BeforeEach
    void setUp() {
        service = new AddressService(addressRepository);
        lenient().when(addressRepository.save(any(CustomerAddress.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private AddressRequest request(boolean makeDefault) {
        return new AddressRequest("Home", "Alice", "9812345678",
                "12 MG Road", "Pune", "Maharashtra", "411001", makeDefault);
    }

    private CustomerAddress owned(Long id, Long userId, boolean isDefault) {
        CustomerAddress a = new CustomerAddress(userId, "Home", "Owner", "9812345678",
                "1 St", "City", "State", "411001", isDefault);
        ReflectionTestUtils.setField(a, "id", id);
        return a;
    }

    @Test
    void firstAddressBecomesDefaultAutomatically() {
        when(addressRepository.findByUserId(ALICE)).thenReturn(new ArrayList<>());

        AddressResponse response = service.create(ALICE, request(false));

        assertThat(response.isDefault()).isTrue();
    }

    @Test
    void settingNewDefaultClearsPreviousDefault() {
        CustomerAddress existingDefault = owned(10L, ALICE, true);
        when(addressRepository.findByUserId(ALICE))
                .thenReturn(new ArrayList<>(List.of(existingDefault)));

        AddressResponse response = service.create(ALICE, request(true));

        assertThat(response.isDefault()).isTrue();
        // The previously-default address was cleared.
        assertThat(existingDefault.isDefault()).isFalse();
    }

    @Test
    void updatingAnotherUsersAddressIsRejectedAsNotFound() {
        // Bob's address is not visible to Alice (repo scoping returns empty).
        when(addressRepository.findByIdAndUserId(99L, ALICE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(ALICE, 99L, request(false)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(addressRepository, never()).save(any());
    }

    @Test
    void deletingAnotherUsersAddressIsRejectedAsNotFound() {
        when(addressRepository.findByIdAndUserId(99L, ALICE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(ALICE, 99L))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(addressRepository, never()).delete(any());
    }

    @Test
    void setDefaultOnAnotherUsersAddressIsRejectedAsNotFound() {
        when(addressRepository.findByIdAndUserId(99L, ALICE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setDefault(ALICE, 99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void ownerCanUpdateTheirOwnAddress() {
        CustomerAddress mine = owned(5L, BOB, false);
        when(addressRepository.findByIdAndUserId(5L, BOB)).thenReturn(Optional.of(mine));

        AddressResponse response = service.update(BOB, 5L,
                new AddressRequest("Work", "Bob", "9800000000",
                        "9 Ring Rd", "Nagpur", "Maharashtra", "440001", false));

        assertThat(response.label()).isEqualTo("Work");
        assertThat(response.city()).isEqualTo("Nagpur");
    }
}
