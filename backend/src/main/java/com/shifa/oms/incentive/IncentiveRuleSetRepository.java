package com.shifa.oms.incentive;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IncentiveRuleSetRepository extends JpaRepository<IncentiveRuleSet, Long> {

    /** Every saved version with its tiers loaded, newest first. */
    @EntityGraph(attributePaths = "tiers")
    List<IncentiveRuleSet> findAllByOrderByEffectiveFromDescIdDesc();
}
