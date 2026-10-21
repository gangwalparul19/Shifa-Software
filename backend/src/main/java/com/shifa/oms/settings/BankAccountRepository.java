package com.shifa.oms.settings;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BankAccountRepository extends JpaRepository<BankAccount, Long> {
    List<BankAccount> findAllByOrderBySortOrderAscIdAsc();
    List<BankAccount> findByActiveTrueOrderBySortOrderAscIdAsc();
    List<BankAccount> findByActiveTrueAndAccountTypeInOrderBySortOrderAscIdAsc(Collection<BankAccountType> types);
    Optional<BankAccount> findFirstByActiveTrueAndDefaultAccountTrueOrderBySortOrderAscIdAsc();
}
