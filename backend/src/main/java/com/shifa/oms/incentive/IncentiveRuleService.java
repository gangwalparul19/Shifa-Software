package com.shifa.oms.incentive;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.auth.User;
import com.shifa.oms.auth.UserRepository;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.incentive.domain.IncentiveFormat;
import com.shifa.oms.incentive.domain.IncentiveRuleDiff;
import com.shifa.oms.incentive.domain.RuleTimeline;
import com.shifa.oms.incentive.domain.RuleTimeline.Tier;
import com.shifa.oms.incentive.domain.RuleTimeline.Version;
import com.shifa.oms.incentive.dto.IncentiveRules.RuleVersionView;
import com.shifa.oms.incentive.dto.IncentiveRules.RulesRequest;
import com.shifa.oms.incentive.dto.IncentiveRules.RulesResponse;
import com.shifa.oms.incentive.dto.IncentiveRules.TierRequest;
import com.shifa.oms.incentive.dto.IncentiveRules.TierView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The daily incentive slabs and their history.
 *
 * <p>Every save adds a new, immutable version; nothing is edited in place. That keeps the full
 * trail (who, when, what changed) and lets the tracker judge each past day by the rules that were in
 * force then. A version with no slabs switches the daily bonus off from its start day.
 */
@Service
public class IncentiveRuleService {

    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

    private static final int MAX_TIERS = 20;
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("10000000.00");
    private static final LocalDate EARLIEST_START = LocalDate.of(2020, 1, 1);
    private static final int MAX_DAYS_AHEAD = 366;

    private final IncentiveRuleSetRepository repository;
    private final CurrentUserService currentUserService;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final Clock clock;

    @Autowired
    public IncentiveRuleService(IncentiveRuleSetRepository repository,
                                CurrentUserService currentUserService,
                                UserRepository userRepository,
                                AuditService auditService) {
        this(repository, currentUserService, userRepository, auditService, Clock.system(BUSINESS_ZONE));
    }

    IncentiveRuleService(IncentiveRuleSetRepository repository,
                         CurrentUserService currentUserService,
                         UserRepository userRepository,
                         AuditService auditService,
                         Clock clock) {
        this.repository = repository;
        this.currentUserService = currentUserService;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    /** Every saved version, newest first, with the one in force today marked. */
    @Transactional(readOnly = true)
    public RulesResponse rules() {
        LocalDate today = LocalDate.now(clock);
        List<IncentiveRuleSet> all = repository.findAllByOrderByEffectiveFromDescIdDesc();
        RuleTimeline timeline = timelineOf(all);
        Long activeId = timeline.inForce(today).map(Version::id).orElse(null);
        List<RuleVersionView> views = new ArrayList<>();
        RuleVersionView inForce = null;
        for (IncentiveRuleSet set : all) {
            RuleVersionView view = view(set, statusOf(set, all, activeId, today));
            views.add(view);
            if (set.getId().equals(activeId)) {
                inForce = view;
            }
        }
        return new RulesResponse(today, inForce, views);
    }

    /** The rules over time, for the tracker. */
    @Transactional(readOnly = true)
    public RuleTimeline timeline() {
        return timelineOf(repository.findAllByOrderByEffectiveFromDescIdDesc());
    }

    /** Views of the given saved versions, oldest first (used to show which rules a month used). */
    @Transactional(readOnly = true)
    public List<RuleVersionView> viewsOf(java.util.Collection<Long> ids) {
        LocalDate today = LocalDate.now(clock);
        List<IncentiveRuleSet> all = repository.findAllByOrderByEffectiveFromDescIdDesc();
        Long activeId = timelineOf(all).inForce(today).map(Version::id).orElse(null);
        return all.stream()
                .filter(set -> ids.contains(set.getId()))
                .sorted(Comparator.comparing(IncentiveRuleSet::getEffectiveFrom)
                        .thenComparing(IncentiveRuleSet::getId))
                .map(set -> view(set, statusOf(set, all, activeId, today)))
                .toList();
    }

    /** Saves a new version of the rules and records who changed what. */
    @Transactional
    public RulesResponse save(RulesRequest request) {
        LocalDate today = LocalDate.now(clock);
        LocalDate effectiveFrom = request.effectiveFrom();
        if (effectiveFrom.isBefore(EARLIEST_START) || effectiveFrom.isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
            throw new ValidationException("Choose a start day between " + EARLIEST_START
                    + " and " + today.plusDays(MAX_DAYS_AHEAD) + ".");
        }
        List<Tier> tiers = validatedTiers(request.tiers());

        // Compare with the rules this version replaces: whatever is in force on its start day.
        List<IncentiveRuleSet> existing = repository.findAllByOrderByEffectiveFromDescIdDesc();
        List<Tier> before = timelineOf(existing).inForce(effectiveFrom)
                .map(Version::tiers)
                .orElse(null);
        String summary = IncentiveRuleDiff.describe(effectiveFrom, before, tiers);

        AuthPrincipal actor = currentUserService.currentUser().orElse(null);
        Long actorId = actor == null ? null : actor.userId();
        String actorName = actorName(actor);
        String note = request.note() == null || request.note().isBlank() ? null : request.note().trim();

        IncentiveRuleSet set = new IncentiveRuleSet(effectiveFrom, note, summary, actorId, actorName);
        for (Tier tier : tiers) {
            set.addTier(new IncentiveTier(tier.minSales(), tier.bonus()));
        }
        IncentiveRuleSet saved = repository.save(set);

        auditService.record(AuditActions.INCENTIVE_RULES_UPDATED, AuditActions.ENTITY_INCENTIVE_RULES,
                String.valueOf(saved.getId()),
                "Daily incentive rules changed. " + summary + (note == null ? "" : " Note: " + note));
        return rules();
    }

    // --- Helpers -----------------------------------------------------------

    /** Cleans and checks the slabs; returns them in ascending order. */
    private static List<Tier> validatedTiers(List<TierRequest> requested) {
        List<TierRequest> input = requested == null ? List.of() : requested;
        if (input.size() > MAX_TIERS) {
            throw new ValidationException("Use at most " + MAX_TIERS + " slabs.");
        }
        List<Tier> tiers = new ArrayList<>();
        for (TierRequest t : input) {
            BigDecimal min = t.minSales();
            BigDecimal bonus = t.bonus();
            if (min.signum() <= 0) {
                throw new ValidationException("Each slab needs daily sales above zero.");
            }
            if (bonus.signum() <= 0) {
                throw new ValidationException("Each slab needs a bonus above zero.");
            }
            if (min.stripTrailingZeros().scale() > 2 || bonus.stripTrailingZeros().scale() > 2) {
                throw new ValidationException("Amounts can have at most two decimal places.");
            }
            if (min.compareTo(MAX_AMOUNT) > 0 || bonus.compareTo(MAX_AMOUNT) > 0) {
                throw new ValidationException("Amounts must be at most " + IncentiveFormat.inr(MAX_AMOUNT) + ".");
            }
            tiers.add(new Tier(min.setScale(2, java.math.RoundingMode.UNNECESSARY), bonus.setScale(2)));
        }
        tiers.sort(Comparator.comparing(Tier::minSales));
        for (int i = 1; i < tiers.size(); i++) {
            Tier previous = tiers.get(i - 1);
            Tier current = tiers.get(i);
            if (previous.minSales().compareTo(current.minSales()) == 0) {
                throw new ValidationException("Two slabs start at " + IncentiveFormat.inr(current.minSales())
                        + ". Each slab needs its own sales amount.");
            }
            if (current.bonus().compareTo(previous.bonus()) <= 0) {
                throw new ValidationException("The " + IncentiveFormat.inr(current.minSales())
                        + " slab must pay more than the " + IncentiveFormat.inr(previous.minSales())
                        + " slab. A higher day should never earn the same or less.");
            }
        }
        return tiers;
    }

    private String actorName(AuthPrincipal actor) {
        if (actor == null) {
            return null;
        }
        String name = userRepository.findById(actor.userId())
                .map(User::getFullName)
                .filter(n -> n != null && !n.isBlank())
                .orElse(actor.username());
        return name.length() > 100 ? name.substring(0, 100) : name;
    }

    private static RuleTimeline timelineOf(List<IncentiveRuleSet> sets) {
        List<Version> versions = new ArrayList<>();
        for (IncentiveRuleSet set : sets) {
            List<Tier> tiers = new ArrayList<>();
            for (IncentiveTier t : set.getTiers()) {
                tiers.add(new Tier(t.getMinDailySales(), t.getBonusAmount()));
            }
            versions.add(new Version(set.getId(), set.getEffectiveFrom(), tiers));
        }
        return new RuleTimeline(versions);
    }

    private static String statusOf(IncentiveRuleSet set, List<IncentiveRuleSet> all, Long activeId, LocalDate today) {
        boolean replaced = all.stream().anyMatch(other ->
                other.getEffectiveFrom().equals(set.getEffectiveFrom()) && other.getId() > set.getId());
        if (replaced) {
            return "REPLACED";
        }
        if (set.getId().equals(activeId)) {
            return "ACTIVE";
        }
        return set.getEffectiveFrom().isAfter(today) ? "SCHEDULED" : "PREVIOUS";
    }

    private static RuleVersionView view(IncentiveRuleSet set, String status) {
        List<TierView> tiers = set.getTiers().stream()
                .map(t -> new TierView(t.getMinDailySales(), t.getBonusAmount()))
                .toList();
        return new RuleVersionView(set.getId(), set.getEffectiveFrom(), tiers, set.getNote(),
                set.getChangeSummary(), set.getCreatedById(), set.getCreatedByName(), set.getCreatedAt(), status);
    }
}
