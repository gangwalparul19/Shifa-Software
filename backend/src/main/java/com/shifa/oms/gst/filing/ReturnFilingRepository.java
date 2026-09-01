package com.shifa.oms.gst.filing;

import com.shifa.oms.gst.filing.domain.ReturnType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data repository for {@link ReturnFiling} (GST returns &amp; filing, Reqs 1.1, 1.2, 1.5, 2.4,
 * 2.8).
 *
 * <p>The filing-status lifecycle is keyed by {@code (period_year, period_month, return_type)} — a
 * missing row denotes {@link com.shifa.oms.gst.filing.domain.FilingStatus#NOT_STARTED} (Req 1.2), so
 * the service resolves status by looking the row up and treating {@link Optional#empty()} as
 * NOT_STARTED, creating a row lazily on the first {@code prepare}.
 */
public interface ReturnFilingRepository extends JpaRepository<ReturnFiling, Long> {

    /**
     * The filing row for a given period and return type, if one exists. An empty result means the
     * return is {@code NOT_STARTED} (no row has been created yet, Req 1.2).
     */
    Optional<ReturnFiling> findByPeriodYearAndPeriodMonthAndReturnType(int periodYear,
                                                                       int periodMonth,
                                                                       ReturnType returnType);
}
