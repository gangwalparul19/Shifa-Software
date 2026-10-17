package com.shifa.oms.lead;

import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.lead.dto.LeadSourceRequest;
import com.shifa.oms.lead.dto.LeadSourceResponse;
import com.shifa.oms.lead.dto.LeadTagRequest;
import com.shifa.oms.lead.dto.LeadTagResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * Admin CRM-vocabulary management (CRM expansion Phase 3, Req 18): full
 * {@code lead_tags} CRUD (create / rename / retire / optional stage association,
 * extending the Phase-1 read-only tag list) and the managed {@code lead_sources}
 * availability list (create / rename / activate-deactivate).
 *
 * <p>Additive: the capture picker reads the active lists; the strongly-typed
 * {@code LeadSource} enum stays the compile-time default, so these tables drive
 * the admin-managed availability list + UI pickers only.
 */
@Service
public class LeadConfigService {

    private final LeadTagRepository tagRepository;
    private final ManagedLeadSourceRepository sourceRepository;

    public LeadConfigService(LeadTagRepository tagRepository,
                             ManagedLeadSourceRepository sourceRepository) {
        this.tagRepository = Objects.requireNonNull(tagRepository, "tagRepository");
        this.sourceRepository = Objects.requireNonNull(sourceRepository, "sourceRepository");
    }

    // --- Lead tags (admin CRUD) --------------------------------------------

    /** All tags (active + retired) for the admin view, in display order. */
    @Transactional(readOnly = true)
    public List<LeadTagResponse> listAllTags() {
        return tagRepository.findAll().stream()
                .sorted(java.util.Comparator.comparingInt(LeadTag::getSortOrder)
                        .thenComparing(LeadTag::getId))
                .map(LeadTagResponse::from)
                .toList();
    }

    /** Create a custom tag. Names are unique case-insensitively. */
    @Transactional
    public LeadTagResponse createTag(LeadTagRequest request) {
        String name = requireName(request.name(), "Tag");
        tagRepository.findByNameIgnoreCase(name).ifPresent(t -> {
            throw new ValidationException("A tag named \"" + name + "\" already exists.");
        });
        LeadTag tag = new LeadTag(name, trimToNull(request.color()), "CUSTOM",
                request.sortOrder() != null ? request.sortOrder() : 0);
        tag.setActive(request.active() == null || request.active());
        tag.setStageId(request.stageId());
        return LeadTagResponse.from(tagRepository.save(tag));
    }

    /** Rename / retire / recolour / reorder / (re)associate a stage on a tag. */
    @Transactional
    public LeadTagResponse updateTag(Long id, LeadTagRequest request) {
        LeadTag tag = tagRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tag " + id + " does not exist."));
        String name = requireName(request.name(), "Tag");
        tagRepository.findByNameIgnoreCase(name)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new ValidationException("A tag named \"" + name + "\" already exists.");
                });
        tag.setName(name);
        tag.setColor(trimToNull(request.color()));
        if (request.sortOrder() != null) {
            tag.setSortOrder(request.sortOrder());
        }
        if (request.active() != null) {
            tag.setActive(request.active());
        }
        tag.setStageId(request.stageId());
        return LeadTagResponse.from(tagRepository.save(tag));
    }

    /** Retire a tag (active=false) — retire &ne; delete so existing assignments stay valid. */
    @Transactional
    public LeadTagResponse retireTag(Long id) {
        LeadTag tag = tagRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tag " + id + " does not exist."));
        tag.setActive(false);
        return LeadTagResponse.from(tagRepository.save(tag));
    }

    // --- Lead sources (admin CRUD) -----------------------------------------

    /** All sources (active + retired) for the admin view, in display order. */
    @Transactional(readOnly = true)
    public List<LeadSourceResponse> listAllSources() {
        return sourceRepository.findAllByOrderBySortOrderAscIdAsc().stream()
                .map(LeadSourceResponse::from)
                .toList();
    }

    /** The active sources, in display order — for the capture picker. */
    @Transactional(readOnly = true)
    public List<LeadSourceResponse> listActiveSources() {
        return sourceRepository.findByActiveTrueOrderBySortOrderAscIdAsc().stream()
                .map(LeadSourceResponse::from)
                .toList();
    }

    /** Create a managed source. Names are unique case-insensitively. */
    @Transactional
    public LeadSourceResponse createSource(LeadSourceRequest request) {
        String name = requireName(request.name(), "Source");
        sourceRepository.findByNameIgnoreCase(name).ifPresent(s -> {
            throw new ValidationException("A source named \"" + name + "\" already exists.");
        });
        ManagedLeadSource source = new ManagedLeadSource(name,
                request.active() == null || request.active(),
                request.sortOrder() != null ? request.sortOrder() : 0);
        return LeadSourceResponse.from(sourceRepository.save(source));
    }

    /** Rename / activate-deactivate / reorder a managed source. */
    @Transactional
    public LeadSourceResponse updateSource(Long id, LeadSourceRequest request) {
        ManagedLeadSource source = sourceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Source " + id + " does not exist."));
        String name = requireName(request.name(), "Source");
        sourceRepository.findByNameIgnoreCase(name)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new ValidationException("A source named \"" + name + "\" already exists.");
                });
        source.setName(name);
        if (request.sortOrder() != null) {
            source.setSortOrder(request.sortOrder());
        }
        if (request.active() != null) {
            source.setActive(request.active());
        }
        return LeadSourceResponse.from(sourceRepository.save(source));
    }

    private static String requireName(String name, String what) {
        if (name == null || name.isBlank()) {
            throw new ValidationException(what + " name is required.");
        }
        return name.trim();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
