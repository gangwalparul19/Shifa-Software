package com.shifa.oms.common;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Helper that turns the raw {@code page} / {@code size} / {@code sort} query
 * parameters used by the admin list endpoints into a safe Spring Data
 * {@link Pageable} (ROADMAP 2.1/2.2 "Wave 2" server-side paging).
 *
 * <p>It enforces the platform conventions so no endpoint can be abused into an
 * unbounded scan or an injection via the sort field:
 * <ul>
 *   <li>{@code page} is clamped to {@code >= 0} (default {@code 0});</li>
 *   <li>{@code size} defaults to {@value #DEFAULT_SIZE} and is capped at
 *       {@value #MAX_SIZE} (and floored at 1);</li>
 *   <li>{@code sort} is parsed as {@code field[,dir]} and the field is validated
 *       against a per-endpoint whitelist mapping the API field name to the JPA
 *       property; an unknown/blank field falls back to the supplied default
 *       sort.</li>
 * </ul>
 */
public final class PageRequests {

    /** Default page size when the caller does not specify one. */
    public static final int DEFAULT_SIZE = 20;

    /** Hard cap on page size to protect the database from large scans. */
    public static final int MAX_SIZE = 100;

    private PageRequests() {
    }

    /**
     * Builds a {@link Pageable} from the raw request params.
     *
     * @param page          zero-based page index (nullable → 0)
     * @param size          page size (nullable → {@link #DEFAULT_SIZE}; capped at {@link #MAX_SIZE})
     * @param sort          {@code field[,asc|desc]} (nullable/blank → {@code defaultSort})
     * @param sortWhitelist maps an accepted API sort field to its JPA property name
     * @param defaultSort   the sort applied when {@code sort} is absent or not whitelisted
     */
    public static Pageable of(Integer page, Integer size, String sort,
                              Map<String, String> sortWhitelist, Sort defaultSort) {
        int pageIndex = (page == null || page < 0) ? 0 : page;
        int pageSize = (size == null) ? DEFAULT_SIZE : size;
        if (pageSize < 1) {
            pageSize = DEFAULT_SIZE;
        }
        if (pageSize > MAX_SIZE) {
            pageSize = MAX_SIZE;
        }
        Sort resolved = parseSort(sort, sortWhitelist, defaultSort);
        return PageRequest.of(pageIndex, pageSize, resolved);
    }

    /**
     * Parses a comma-separated {@code sort} parameter (optionally repeated as
     * multiple {@code field,dir} orders separated by ';') against a whitelist.
     * Only whitelisted fields are honoured; if none resolve, {@code defaultSort}
     * is returned.
     */
    private static Sort parseSort(String sort, Map<String, String> sortWhitelist, Sort defaultSort) {
        if (sort == null || sort.isBlank() || sortWhitelist == null || sortWhitelist.isEmpty()) {
            return defaultSort;
        }
        List<Sort.Order> orders = new ArrayList<>();
        for (String clause : sort.split(";")) {
            String[] parts = clause.trim().split(",");
            if (parts.length == 0 || parts[0].isBlank()) {
                continue;
            }
            String property = sortWhitelist.get(parts[0].trim());
            if (property == null) {
                continue;
            }
            Sort.Direction direction = (parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim()))
                    ? Sort.Direction.DESC
                    : Sort.Direction.ASC;
            orders.add(new Sort.Order(direction, property));
        }
        return orders.isEmpty() ? defaultSort : Sort.by(orders);
    }
}
