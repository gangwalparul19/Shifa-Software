package com.shifa.oms.account;

import com.shifa.oms.account.dto.AccountOrderSummary;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.order.dto.OrderResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Customer order-history endpoints ({@code /api/account/orders}). A
 * {@code CUSTOMER} sees only their own orders (account-linked or matching their
 * saved mobile). Invoices are downloaded via the public
 * {@code /api/track/{orderCode}/invoice} endpoint using the codes returned here.
 */
@RestController
@RequestMapping("/api/account/orders")
@PreAuthorize("hasRole('CUSTOMER')")
public class AccountOrderController {

    private final AccountOrderService accountOrderService;
    private final CurrentUserService currentUserService;

    public AccountOrderController(AccountOrderService accountOrderService,
                                  CurrentUserService currentUserService) {
        this.accountOrderService = accountOrderService;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public List<AccountOrderSummary> history() {
        return accountOrderService.history(currentUserId());
    }

    @GetMapping("/{orderCode}")
    public OrderResponse detail(@PathVariable String orderCode) {
        return accountOrderService.detail(currentUserId(), orderCode);
    }

    private Long currentUserId() {
        return currentUserService.requireCurrentUser().userId();
    }
}
