package com.shifa.oms.integration.shopify;

import com.shifa.oms.integration.shopify.dto.ReviewQueueRow;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The ADMIN Shopify review queue (Req 3.9).
 *
 * <p>Mounted on the existing {@code /api/admin/orders} base path with a literal sub-path.
 * Spring matches a literal segment ahead of the {@code /{id}} template on
 * {@code AdminOrderController}, so this coexists without touching that controller or its
 * tests — the same arrangement {@code CustomerCrmController} already uses alongside
 * {@code CustomerController}.
 *
 * <p>ADMIN only: the queue exposes every channel's orders and the reasons they are
 * imperfect, which is administrative rather than sales information.
 */
@RestController
@RequestMapping("/api/admin/orders")
@PreAuthorize("hasRole('ADMIN')")
public class ReviewQueueController {

    private final ReviewQueueService reviewQueueService;

    public ReviewQueueController(ReviewQueueService reviewQueueService) {
        this.reviewQueueService = reviewQueueService;
    }

    /** Every order awaiting review after Shopify ingestion, newest first. */
    @GetMapping("/review-queue")
    public List<ReviewQueueRow> reviewQueue() {
        return reviewQueueService.list();
    }
}
