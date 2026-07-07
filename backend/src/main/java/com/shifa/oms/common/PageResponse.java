package com.shifa.oms.common;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * A small, stable paged-response envelope returned by the admin list endpoints
 * that back the big data-tables (ROADMAP 2.1/2.2 "Wave 2"): orders, products,
 * and reconciliation receivables.
 *
 * <p>The shape is deliberately flat and framework-agnostic (rather than Spring's
 * {@code PageImpl} JSON, whose structure is not guaranteed stable across
 * versions) so the Angular admin tables have a small, documented contract:
 *
 * <pre>{ content: [...], page, size, totalElements, totalPages }</pre>
 *
 * @param content       the rows for the requested page
 * @param page          the zero-based page index that was returned
 * @param size          the page size that was applied (after capping)
 * @param totalElements total number of matching rows across all pages
 * @param totalPages    total number of pages available
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    /** Wraps a Spring Data {@link Page} into the flat envelope. */
    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }

    /**
     * Maps a {@link Page} of entities to a {@link PageResponse} of DTOs using the
     * given mapper, preserving the paging metadata.
     */
    public static <E, T> PageResponse<T> of(Page<E> page, java.util.function.Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
