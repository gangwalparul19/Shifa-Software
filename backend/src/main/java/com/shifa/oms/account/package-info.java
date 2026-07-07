/**
 * Customer self-service account module (Phase B).
 *
 * <p>Provides storefront customers with an authenticated account area on top of
 * the shared auth/user model: a saved address book ({@link com.shifa.oms.account.AddressService}),
 * profile view/update ({@link com.shifa.oms.account.ProfileService}), a
 * persisted wishlist ({@link com.shifa.oms.account.WishlistService}), and order
 * history ({@link com.shifa.oms.account.AccountOrderService}).
 *
 * <p>Every operation is scoped to the calling customer's user id so a customer
 * can only ever read or modify their own data. All endpoints live under
 * {@code /api/account/**} and require the {@code CUSTOMER} role. Registration and
 * login are handled by the {@code auth} module.
 */
package com.shifa.oms.account;
