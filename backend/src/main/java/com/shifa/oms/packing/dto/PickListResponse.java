package com.shifa.oms.packing.dto;

import java.util.List;

/**
 * The daily pick-list / packing manifest (enhancement: "Daily pick-list /
 * packing manifest"): every product needed across all orders awaiting packing
 * ({@code Label_Generated}), aggregated into one sheet so the packer picks
 * stock once per product instead of walking the stock room per order.
 *
 * @param orderCount total distinct orders contributing to the list
 * @param lines      one row per product, sorted by total quantity descending
 *                   (pick the highest-volume items first)
 */
public record PickListResponse(
        int orderCount,
        List<PickListLine> lines
) {

    /** One aggregated product row on the pick-list. */
    public record PickListLine(
            Long productId,
            String productName,
            String sku,
            int totalQuantity,
            /** How many distinct orders need this product (for context, not a sum). */
            int orderCount
    ) {
    }
}
