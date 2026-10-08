package com.shifa.oms;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Full-context DI smoke test.
 *
 * <p>Boots the entire Spring {@code ApplicationContext} against an in-memory H2
 * database (the {@code smoketest} profile) so that bean-wiring failures are
 * caught by the build before a deploy — in particular the class of failure that
 * bit this project before: a {@code @Service} with two constructors where
 * neither is {@code @Autowired} ("No default constructor found"), which only
 * surfaces when the context actually instantiates the bean, not in the
 * sliced/stubbed guard tests. If the context fails to start, this test fails.
 */
@SpringBootTest
@ActiveProfiles("smoketest")
class ApplicationContextLoadsTest {

    @Test
    void contextLoads() {
        // Intentionally empty: success is the context starting without error.
    }
}
