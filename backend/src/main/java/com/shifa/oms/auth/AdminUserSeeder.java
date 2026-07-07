package com.shifa.oms.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds an initial {@link Role#ADMIN} user on startup so the platform is
 * immediately usable and login is testable (design: "seed at least an initial
 * ADMIN user"). Runs only under the {@code local} profile and is idempotent —
 * it creates the user only when no account with the seed username exists, so it
 * never overwrites a changed password.
 *
 * <p>The seed credentials default to {@code admin} / {@code admin123} and can be
 * overridden with the {@code SEED_ADMIN_USERNAME} / {@code SEED_ADMIN_PASSWORD}
 * environment variables. Using the {@link PasswordEncoder} bean here guarantees
 * the stored hash matches the encoder used at login (unlike a hard-coded hash in
 * a migration).
 */
@Component
@Profile("local")
public class AdminUserSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminUserSeeder.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String seedUsername;
    private final String seedPassword;
    private final String seedFullName;
    private final String packingUsername;
    private final String packingPassword;
    private final String packingFullName;

    public AdminUserSeeder(UserRepository userRepository,
                           PasswordEncoder passwordEncoder,
                           @Value("${app.security.seed.admin.username:admin}") String seedUsername,
                           @Value("${app.security.seed.admin.password:admin123}") String seedPassword,
                           @Value("${app.security.seed.admin.full-name:Platform Administrator}") String seedFullName,
                           @Value("${app.security.seed.packing.username:packer}") String packingUsername,
                           @Value("${app.security.seed.packing.password:packer123}") String packingPassword,
                           @Value("${app.security.seed.packing.full-name:Packing Department}") String packingFullName) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.seedUsername = seedUsername;
        this.seedPassword = seedPassword;
        this.seedFullName = seedFullName;
        this.packingUsername = packingUsername;
        this.packingPassword = packingPassword;
        this.packingFullName = packingFullName;
    }

    @Override
    public void run(String... args) {
        seedUser(seedUsername, seedPassword, Role.ADMIN, seedFullName);
        // A PACKING_USER so the packing barcode-scan flow (Req 11) is testable
        // end-to-end in local dev (defaults: packer / packer123).
        seedUser(packingUsername, packingPassword, Role.PACKING_USER, packingFullName);
    }

    /**
     * Idempotently seeds a user: creates it only when no account with that
     * username exists, so a changed password is never overwritten.
     */
    private void seedUser(String username, String password, Role role, String fullName) {
        if (userRepository.findByUsername(username).isPresent()) {
            return; // Already seeded (or created manually); leave it untouched.
        }
        User user = new User(username, passwordEncoder.encode(password), role, fullName, true);
        userRepository.save(user);
        log.info("Seeded initial {} user '{}' (local profile).", role, username);
    }
}
