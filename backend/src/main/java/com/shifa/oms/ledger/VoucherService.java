package com.shifa.oms.ledger;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.ledger.domain.AccountNature;
import com.shifa.oms.ledger.domain.DoubleEntry;
import com.shifa.oms.ledger.domain.DraftVoucher;
import com.shifa.oms.ledger.domain.DrCr;
import com.shifa.oms.ledger.domain.PostingLine;
import com.shifa.oms.ledger.domain.Reversal;
import com.shifa.oms.ledger.domain.VoucherType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The transactional guardian of the General Ledger: posts balanced, immutable double-entry vouchers
 * (General Ledger, Reqs 5.1–5.8, 7.1–7.3, 4.2, 4.3, 6.1, 15.1).
 *
 * <p>{@link #post(PostVoucherCommand)} is the single manual-posting entry point. It delegates every
 * correctness-critical decision to the pure {@code ledger.domain} core — voucher-type parsing to
 * {@link VoucherType#fromName(String)} (Req 7.3) and the double-entry rules (line count, per-line
 * ledger reference and single strictly-positive side, and debit/credit balancing) to
 * {@link DoubleEntry#validate(DraftVoucher)} (Reqs 5.1–5.7) — then layers the service-only concerns:
 * financial-year resolution and the closed-year lock (Reqs 4.2, 4.3), a per-type-per-FY unique
 * voucher reference (Req 5.8), persistence of the posted immutable {@link Voucher} + its
 * {@link VoucherLine}s (Req 6.1), and a {@code VOUCHER_POSTED} audit event (Req 15.1).
 *
 * <p>There is deliberately <strong>no update or delete</strong> operation for a posted voucher; the
 * entity exposes no financial-field setters, so immutability (Reqs 6.1, 6.2) holds structurally.
 * Corrections are made only through a reversing voucher (a later task).
 *
 * <p>Money is normalised to {@link BigDecimal} scale 2 ({@code HALF_UP}), matching the codebase-wide
 * convention; constructor injection and {@code @Transactional} follow the module's service style.
 */
@Service
@Transactional
public class VoucherService {

    /** Money scale matching the codebase-wide {@code BigDecimal} scale-2 {@code HALF_UP} convention. */
    private static final int MONEY_SCALE = 2;

    private final VoucherRepository voucherRepository;
    private final VoucherLineRepository voucherLineRepository;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final AccountGroupRepository accountGroupRepository;
    private final FinancialYearService financialYearService;
    private final VoucherReferenceSequencer voucherReferenceSequencer;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;

    public VoucherService(VoucherRepository voucherRepository,
                          VoucherLineRepository voucherLineRepository,
                          LedgerAccountRepository ledgerAccountRepository,
                          AccountGroupRepository accountGroupRepository,
                          FinancialYearService financialYearService,
                          VoucherReferenceSequencer voucherReferenceSequencer,
                          CurrentUserService currentUserService,
                          AuditService auditService) {
        this.voucherRepository = voucherRepository;
        this.voucherLineRepository = voucherLineRepository;
        this.ledgerAccountRepository = ledgerAccountRepository;
        this.accountGroupRepository = accountGroupRepository;
        this.financialYearService = financialYearService;
        this.voucherReferenceSequencer = voucherReferenceSequencer;
        this.currentUserService = currentUserService;
        this.auditService = auditService;
    }

    /**
     * Posts a manual double-entry voucher (Reqs 5.1–5.8, 7.1–7.3, 4.2, 4.3, 6.1, 15.1).
     *
     * <p>Steps, in order:
     * <ol>
     *   <li>strictly parse the voucher type name; an unsupported name is rejected (Req 7.3);</li>
     *   <li>resolve each line's ledger account to its {@link AccountNature} (derived from the owning
     *       group) and build a {@link DraftVoucher};</li>
     *   <li>run {@link DoubleEntry#validate(DraftVoucher)}; any violation is rejected with a 400, and
     *       an imbalance reports the exact debit total, credit total, and difference (Reqs 5.1–5.7,
     *       and 5.3 in particular);</li>
     *   <li>resolve the financial year that contains the voucher date (Req 4.2) and reject posting
     *       into a closed year (Req 4.3);</li>
     *   <li>allocate a per-type-per-FY unique voucher reference (Req 5.8);</li>
     *   <li>persist the posted, immutable voucher + lines (Req 6.1);</li>
     *   <li>record a {@code VOUCHER_POSTED} audit event (Req 15.1).</li>
     * </ol>
     *
     * @param command the voucher to post (type name, date, narration, and Dr/Cr lines)
     * @return the persisted, posted {@link Voucher}
     * @throws ValidationException       when the type name is unsupported, the draft fails any
     *                                   double-entry rule, or the target financial year is closed
     * @throws ResourceNotFoundException when a referenced ledger account (or its group) does not exist
     */
    public Voucher post(PostVoucherCommand command) {
        if (command == null) {
            throw new ValidationException("A voucher is required.");
        }

        // Req 7.1–7.3: strictly parse the voucher type; reject an unsupported name.
        VoucherType type = VoucherType.fromName(command.type())
                .orElseThrow(() -> new ValidationException(
                        "Unsupported voucher type '" + command.type() + "'. Supported types: "
                                + supportedTypeNames() + "."));

        // Resolve each line's ledger -> nature and build the draft's posting lines.
        List<PostingLine> postingLines = resolvePostingLines(command.lines());
        DraftVoucher draft = new DraftVoucher(type, command.date(), command.narration(), postingLines);

        // Validate + persist the posted, immutable voucher (Reqs 5.1–5.8, 4.2, 4.3, 6.1).
        Voucher voucher = postValidatedDraft(draft, null, null, null);

        // Req 15.1: record who posted which voucher, and when, via the existing audit trail.
        auditService.record(AuditActions.VOUCHER_POSTED, AuditActions.ENTITY_VOUCHER,
                String.valueOf(voucher.getId()),
                "Posted " + type.name() + " voucher " + voucher.getVoucherReference());

        return voucher;
    }

    /**
     * Posts an <strong>auto-derived</strong> double-entry voucher for a source business document,
     * stamping the resulting voucher with its {@code (sourceType, sourceId)} key so re-delivery of the
     * originating event is idempotent at the database (unique voucher source key, Reqs 8.4, 9.3, 10.3,
     * 11.4).
     *
     * <p>This is the entry point the {@code LedgerPostingDrainer} uses for decoupled auto-posting: it
     * reuses the exact same {@link #postValidatedDraft} validation + persistence path as the manual
     * {@link #post(PostVoucherCommand)} (so balancing, closed-FY rejection, unique referencing, and
     * immutability all hold identically), differing only in that the persisted {@link Voucher} carries
     * the source key. The manual {@code post} path is intentionally left unchanged.
     *
     * <p>If the derived draft does not balance (or its financial year is closed), this rejects with a
     * {@link ValidationException} exactly as manual posting would; the drainer catches that, records
     * the outbox row as {@code FAILED}, and never touches the source aggregate (Reqs 8.5, 9.4, 10.4,
     * 17.4).
     *
     * @param draft      the balanced draft derived from the source document
     * @param sourceType the source-document type name persisted on the voucher (ORDER /
     *                   PURCHASE_ORDER / EXPENSE / PAYMENT)
     * @param sourceId   the source-document id persisted on the voucher
     * @return the persisted, posted {@link Voucher} carrying the source key
     * @throws ValidationException when the draft is null, fails any double-entry rule, or targets a
     *                             closed financial year
     */
    public Voucher postForSource(DraftVoucher draft, String sourceType, Long sourceId) {
        if (draft == null) {
            throw new ValidationException("A voucher draft is required.");
        }

        Voucher voucher = postValidatedDraft(draft, null, sourceType, sourceId);

        // Req 15.1: record the auto-post via the existing audit trail, noting the source document.
        auditService.record(AuditActions.VOUCHER_POSTED, AuditActions.ENTITY_VOUCHER,
                String.valueOf(voucher.getId()),
                "Auto-posted " + draft.type().name() + " voucher " + voucher.getVoucherReference()
                        + " from " + sourceType + " #" + sourceId);

        return voucher;
    }

    /**
     * Reverses a posted voucher by posting a balancing reversing voucher (Reqs 6.3, 6.4, 6.5, 15.2).
     *
     * <p>Posted vouchers are structurally immutable; the only compliant correction is a reversing
     * entry that exactly cancels the original line by line. Steps, in order:
     * <ol>
     *   <li>load the posted voucher (a 404 when it does not exist);</li>
     *   <li>reject it if it has already been reversed (Req 6.5) — a voucher may be reversed at most
     *       once;</li>
     *   <li>reconstruct the original's posting lines and negate them via
     *       {@link Reversal#negate(DraftVoucher)}, yielding a balanced reversing draft that reuses the
     *       original's type and date (Req 6.3);</li>
     *   <li>post the reversing draft as a new posted voucher, carrying a {@code reverses} link back to
     *       the original (Req 6.4);</li>
     *   <li>set the {@code reversed_by} link on the original so the two vouchers reference each other
     *       (Req 6.4);</li>
     *   <li>record a {@code VOUCHER_REVERSED} audit event capturing both the original and the
     *       reversing voucher references (Req 15.2).</li>
     * </ol>
     *
     * @param voucherId the id of the posted voucher to reverse
     * @return the persisted, posted reversing {@link Voucher}
     * @throws ValidationException       when the voucher has already been reversed, or the reversing
     *                                   entry cannot be posted (e.g. its financial year is closed)
     * @throws ResourceNotFoundException when no voucher exists for the given id
     */
    public Voucher reverse(Long voucherId) {
        if (voucherId == null) {
            throw new ValidationException("A voucher id is required.");
        }

        Voucher original = voucherRepository.findById(voucherId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Voucher " + voucherId + " was not found."));

        // Req 6.5: a voucher can be reversed at most once.
        if (original.getReversedByVoucherId() != null) {
            throw new ValidationException(
                    "Voucher " + original.getVoucherReference() + " has already been reversed by voucher id "
                            + original.getReversedByVoucherId() + ".");
        }

        // Req 6.3: build the reversing draft by negating the original's lines line-by-line.
        DraftVoucher reversingDraft = Reversal.negate(toDraft(original));

        // Post the reversing voucher, linking it back to the original (Req 6.4).
        Voucher reversing = postValidatedDraft(reversingDraft, original.getId(), null, null);

        // Req 6.4: complete the bidirectional link on the original voucher.
        original.markReversedBy(reversing.getId());
        voucherRepository.save(original);

        // Req 15.2: record the reversal, capturing BOTH the original and reversing references.
        auditService.record(AuditActions.VOUCHER_REVERSED, AuditActions.ENTITY_VOUCHER,
                String.valueOf(reversing.getId()),
                "Reversed voucher " + original.getVoucherReference() + " (id " + original.getId()
                        + ") with reversing voucher " + reversing.getVoucherReference()
                        + " (id " + reversing.getId() + ")");

        return reversing;
    }

    /**
     * Validate a draft against the double-entry rules and persist it as a posted, immutable voucher.
     * Shared by {@link #post(PostVoucherCommand)} and {@link #reverse(Long)}: runs
     * {@link DoubleEntry#validate(DraftVoucher)} (Reqs 5.1–5.7, imbalance totals per Req 5.3), resolves
     * the financial year from the draft date and rejects a closed year (Reqs 4.2, 4.3), allocates a
     * per-type-per-FY unique reference (Req 5.8), and persists the voucher + its lines (Req 6.1).
     *
     * @param draft             the validated-in-full draft to post
     * @param reversesVoucherId the id of the original voucher when posting a reversing voucher, else
     *                          {@code null}
     * @param sourceType        the source-document type name for an auto-posted voucher, else {@code null}
     * @param sourceId          the source-document id for an auto-posted voucher, else {@code null}
     * @return the persisted, posted {@link Voucher}
     */
    private Voucher postValidatedDraft(DraftVoucher draft, Long reversesVoucherId,
                                       String sourceType, Long sourceId) {
        // Reqs 5.1–5.7: enforce the double-entry rules; on imbalance report the exact totals (Req 5.3).
        DoubleEntry.Result result = DoubleEntry.validate(draft);
        if (!result.ok()) {
            throw new ValidationException(rejectionMessage(result), result.violations());
        }

        // Req 4.2: assign the voucher to the financial year that contains its date.
        FinancialYear financialYear = financialYearService.financialYearFor(draft.date());
        // Req 4.3: a closed financial year rejects posting.
        if (financialYearService.isClosed(financialYear.getId())) {
            throw new ValidationException(
                    "Financial year " + financialYear.getLabel()
                            + " is closed; vouchers dated within it cannot be posted.");
        }

        // Req 5.8: allocate a reference unique within this voucher type and financial year.
        String reference = voucherReferenceSequencer.allocate(draft.type(), financialYear);

        // Req 6.1: persist the posted, immutable voucher and its lines.
        String postedBy = currentUserService.currentUser().map(p -> p.username()).orElse(null);
        Voucher voucher = voucherRepository.save(new Voucher(
                draft.type(), draft.date(), financialYear.getId(), reference, draft.narration(),
                postedBy, sourceType, sourceId, reversesVoucherId));

        List<VoucherLine> lines = new ArrayList<>();
        int order = 0;
        for (PostingLine line : draft.lines()) {
            BigDecimal amount = line.amount().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            lines.add(new VoucherLine(
                    voucher.getId(),
                    line.ledgerId(),
                    line.isDebit() ? amount : null,
                    line.isCredit() ? amount : null,
                    order++,
                    null));
        }
        voucherLineRepository.saveAll(lines);

        return voucher;
    }

    /**
     * Reconstruct a posted voucher's {@link DraftVoucher} form from its persisted lines: each line's
     * ledger nature is re-derived from its owning group so {@link Reversal} can negate the draft. The
     * exactly-one-side invariant held at posting, so a non-null {@code debit} marks a debit line and
     * otherwise it is a credit line.
     */
    private DraftVoucher toDraft(Voucher voucher) {
        List<VoucherLine> persistedLines = voucherLineRepository.findByVoucherIdOrderByLineOrderAsc(voucher.getId());
        List<PostingLine> postingLines = new ArrayList<>(persistedLines.size());
        for (VoucherLine line : persistedLines) {
            AccountNature nature = natureOf(line.getLedgerAccountId());
            if (line.getDebit() != null) {
                postingLines.add(PostingLine.debit(line.getLedgerAccountId(), nature, line.getDebit()));
            } else {
                postingLines.add(PostingLine.credit(line.getLedgerAccountId(), nature, line.getCredit()));
            }
        }
        return new DraftVoucher(voucher.getVoucherType(), voucher.getVoucherDate(), voucher.getNarration(),
                postingLines);
    }

    /**
     * Resolve each command line's ledger account to a validated {@link PostingLine} carrying the
     * ledger's derived {@link AccountNature}. A line missing a ledger reference is rejected (Req 5.4);
     * a referenced ledger (or its group) that does not exist yields a 404. Amount and side are carried
     * through as-is so {@link DoubleEntry} can report a non-positive amount (Req 5.6).
     */
    private List<PostingLine> resolvePostingLines(List<VoucherLineCommand> commandLines) {
        List<VoucherLineCommand> source = commandLines == null ? List.of() : commandLines;
        List<PostingLine> lines = new ArrayList<>(source.size());
        for (int i = 0; i < source.size(); i++) {
            VoucherLineCommand line = source.get(i);
            int number = i + 1;
            if (line == null) {
                throw new ValidationException("Voucher line " + number + " is missing.");
            }
            if (line.side() == null) {
                throw new ValidationException("Voucher line " + number + " must specify a debit or credit side.");
            }
            if (line.ledgerAccountId() == null) {
                // Req 5.4: each line must reference exactly one ledger account.
                throw new ValidationException("Voucher line " + number + " must reference a ledger account.");
            }
            AccountNature nature = natureOf(line.ledgerAccountId());
            lines.add(new PostingLine(line.ledgerAccountId(), nature, line.side(), line.amount()));
        }
        return lines;
    }

    /** The {@link AccountNature} of a ledger account, derived from its owning group (Req 2.2). */
    private AccountNature natureOf(Long ledgerAccountId) {
        LedgerAccount ledger = ledgerAccountRepository.findById(ledgerAccountId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Ledger account " + ledgerAccountId + " was not found."));
        AccountGroup group = accountGroupRepository.findById(ledger.getAccountGroupId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Account group " + ledger.getAccountGroupId() + " was not found."));
        return group.getNature();
    }

    /**
     * Build the rejection message for a failed validation. For an imbalance the message states the
     * exact debit total, credit total, and difference (Req 5.3); otherwise the first violation is
     * surfaced as the summary and the full ordered list is carried as details.
     */
    private static String rejectionMessage(DoubleEntry.Result result) {
        DoubleEntry.BalanceCheck balance = result.balance();
        if (!balance.balanced()) {
            return "Voucher is not balanced: debit total " + balance.debitTotal().toPlainString()
                    + " does not equal credit total " + balance.creditTotal().toPlainString()
                    + " (difference " + balance.difference().toPlainString() + ").";
        }
        List<String> violations = result.violations();
        return violations.isEmpty() ? "Voucher failed validation." : violations.get(0);
    }

    private static String supportedTypeNames() {
        StringBuilder sb = new StringBuilder();
        VoucherType[] types = VoucherType.values();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(types[i].name());
        }
        return sb.toString();
    }

    /**
     * A request to post a manual voucher (method-level input; the REST layer adapts its DTOs to this
     * in a later task). Carries the voucher-type <em>name</em> (parsed strictly so an unsupported name
     * is rejected, Req 7.3), the voucher date, the narration, and the ordered Dr/Cr lines.
     *
     * @param type      the voucher-type name (e.g. {@code "JOURNAL"}); parsed via {@link VoucherType#fromName}
     * @param date      the voucher date
     * @param narration the voucher narration
     * @param lines     the ordered posting lines
     */
    public record PostVoucherCommand(String type, LocalDate date, String narration, List<VoucherLineCommand> lines) {
    }

    /**
     * One line of a {@link PostVoucherCommand}: the ledger account to post against, the side
     * ({@link DrCr#DEBIT}/{@link DrCr#CREDIT}), and the amount.
     *
     * @param ledgerAccountId the ledger account this line posts against
     * @param side            the debit or credit side
     * @param amount          the posting amount (must be strictly positive to pass validation)
     */
    public record VoucherLineCommand(Long ledgerAccountId, DrCr side, BigDecimal amount) {
    }
}
