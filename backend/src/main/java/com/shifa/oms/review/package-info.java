/**
 * Product reviews &amp; ratings (Phase C).
 *
 * <p>Customers submit reviews (rating 1..5 + optional title/body) for published
 * products; each starts PENDING and is flagged verified when the customer has
 * purchased the product. Admins moderate reviews (approve/reject). Only APPROVED
 * reviews are shown publicly and contribute to a product's average rating +
 * count, which the catalog/detail responses surface via {@link
 * com.shifa.oms.product.ProductRatingLookup}.
 */
package com.shifa.oms.review;
