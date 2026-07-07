package com.shifa.oms.courier;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Spring Data repository for {@link CourierCompany} rows. */
public interface CourierCompanyRepository extends JpaRepository<CourierCompany, Long> {

    /** The first company with the given name, if any (used by the local seeder). */
    Optional<CourierCompany> findFirstByName(String name);
}
