package com.shifa.oms.geo;

import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.geo.dto.DeliveryStateRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Application service for the {@link DeliveryState} master list that backs the
 * New Order state typeahead and the admin Settings management table.
 *
 * <p>Reads ({@link #activeNames()}, {@link #listAll()}) are open to any staff /
 * admin respectively at the controller; writes ({@link #create}, {@link #update},
 * {@link #delete}) are admin-only. Names are unique case-insensitively so the
 * typeahead never shows near-duplicate entries.
 */
@Service
public class DeliveryStateService {

    private final DeliveryStateRepository repository;

    public DeliveryStateService(DeliveryStateRepository repository) {
        this.repository = repository;
    }

    /** Active state names for the order-entry typeahead, ordered for display. */
    @Transactional(readOnly = true)
    public List<String> activeNames() {
        return repository.findByActiveTrueOrderBySortOrderAscNameAsc().stream()
                .map(DeliveryState::getName)
                .toList();
    }

    /** All states (active + inactive) for admin management, ordered for display. */
    @Transactional(readOnly = true)
    public List<DeliveryState> listAll() {
        return repository.findAllByOrderBySortOrderAscNameAsc();
    }

    /** Adds a new state, rejecting a blank or duplicate name (409-style validation). */
    @Transactional
    public DeliveryState create(DeliveryStateRequest request) {
        String name = requireName(request.name());
        requireUniqueName(name, null);
        DeliveryState state = new DeliveryState(
                name,
                request.active() == null || request.active(),
                request.sortOrder() != null ? request.sortOrder() : 0);
        return repository.save(state);
    }

    /** Renames / re-orders / toggles a state, rejecting a duplicate rename. */
    @Transactional
    public DeliveryState update(Long id, DeliveryStateRequest request) {
        DeliveryState state = require(id);
        String name = requireName(request.name());
        requireUniqueName(name, id);
        state.setName(name);
        if (request.active() != null) {
            state.setActive(request.active());
        }
        if (request.sortOrder() != null) {
            state.setSortOrder(request.sortOrder());
        }
        return repository.save(state);
    }

    /** Removes a state from the master list. */
    @Transactional
    public void delete(Long id) {
        DeliveryState state = require(id);
        repository.delete(state);
    }

    private DeliveryState require(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("State " + id + " does not exist."));
    }

    private String requireName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            throw new ValidationException("A state name is required.");
        }
        return name;
    }

    private void requireUniqueName(String name, Long selfId) {
        Optional<DeliveryState> existing = repository.findByNameIgnoreCase(name);
        if (existing.isPresent() && !existing.get().getId().equals(selfId)) {
            throw new ValidationException("A state named \"" + name + "\" already exists.");
        }
    }
}
