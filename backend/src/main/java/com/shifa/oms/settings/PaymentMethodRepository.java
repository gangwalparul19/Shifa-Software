package com.shifa.oms.settings;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PaymentMethodRepository extends JpaRepository<PaymentMethod, Long> {

    List<PaymentMethod> findAllByOrderBySortOrderAscIdAsc();

    List<PaymentMethod> findByActiveTrueOrderBySortOrderAscIdAsc();

    Optional<PaymentMethod> findByCodeIgnoreCase(String code);

    Optional<PaymentMethod> findByLabelIgnoreCase(String label);

    boolean existsByCodeIgnoreCase(String code);

    Optional<PaymentMethod> findFirstByActiveTrueAndDefaultMethodTrueOrderBySortOrderAscIdAsc();

    /**
     * The method whose payments are received in an account of the given purpose (for example
     * "Credit Card Link" for {@link BankAccountType#SHOPIFY_SETTLEMENT}). An active method wins
     * over an inactive one, so deactivating it does not change what a new Shopify payment is
     * booked under as long as another linked method exists.
     */
    Optional<PaymentMethod> findFirstByLinkedAccountTypeOrderByActiveDescSortOrderAscIdAsc(
            BankAccountType linkedAccountType);

    /**
     * How many payment transactions carry each method code. Native on purpose: the settings module
     * must not depend on the order module's entities. Used to decide delete vs deactivate.
     */
    @Query(value = "select p.payment_method as code, count(*) as total from payments p "
            + "where p.payment_method is not null group by p.payment_method", nativeQuery = true)
    List<UsageCount> usageCounts();

    @Query(value = "select count(*) from payments p where p.payment_method = :code", nativeQuery = true)
    long countUsage(@org.springframework.data.repository.query.Param("code") String code);

    /** Projection for {@link #usageCounts()}. */
    interface UsageCount {
        String getCode();
        long getTotal();
    }
}
