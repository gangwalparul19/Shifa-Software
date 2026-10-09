package com.shifa.oms.order;

import com.shifa.oms.platform.brand.BrandProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.function.Predicate;

/**
 * Generates unique, human-readable order codes of the form
 * {@code <prefix>yyyyMMdd-XXXX} (design: {@code orders.order_code} is the
 * human/barcode value used by the label service, task 11).
 *
 * <p>The prefix is white-label config ({@code app.brand.order-code-prefix},
 * default {@code "SHR-"} so the Shifa prod build is unchanged); a per-client or
 * demo deployment sets its own so codes never collide across deployments. The
 * {@code XXXX} suffix is four random uppercase base-36 characters. The caller
 * supplies a uniqueness predicate (backed by the DB unique constraint) so the
 * rare collision is retried a bounded number of times.
 */
@Component
public class OrderCodeGenerator {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final char[] ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private static final int SUFFIX_LENGTH = 4;
    private static final int MAX_ATTEMPTS = 20;
    private static final String DEFAULT_PREFIX = "SHR-";

    private final Clock clock;
    private final String prefix;
    private final SecureRandom random = new SecureRandom();

    public OrderCodeGenerator() {
        this(Clock.systemDefaultZone(), DEFAULT_PREFIX);
    }

    public OrderCodeGenerator(Clock clock) {
        this(clock, DEFAULT_PREFIX);
    }

    /** Spring-wired constructor: the order-code prefix comes from app.brand.*. */
    @Autowired
    public OrderCodeGenerator(BrandProperties brand) {
        this(Clock.systemDefaultZone(), brand.orderCodePrefix());
    }

    public OrderCodeGenerator(Clock clock, String prefix) {
        this.clock = clock;
        this.prefix = (prefix == null || prefix.isBlank()) ? DEFAULT_PREFIX : prefix;
    }

    /**
     * Produces an order code not already in use according to {@code exists}.
     *
     * @param exists predicate that returns {@code true} when a candidate code is
     *               already taken (e.g. {@code orderRepository::existsByOrderCode})
     * @return a unique order code
     * @throws IllegalStateException if a unique code could not be produced
     */
    public String generate(Predicate<String> exists) {
        String datePart = LocalDate.now(clock).format(DATE);
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = prefix + datePart + "-" + randomSuffix();
            if (!exists.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "Unable to generate a unique order code after " + MAX_ATTEMPTS + " attempts");
    }

    private String randomSuffix() {
        StringBuilder sb = new StringBuilder(SUFFIX_LENGTH);
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
