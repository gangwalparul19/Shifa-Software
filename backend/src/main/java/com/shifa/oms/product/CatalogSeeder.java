package com.shifa.oms.product;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Seeds a curated catalog of realistic herbal/ayurvedic products on startup so
 * the storefront has attractive, client-ready content out of the box. Runs only
 * under the {@code local} profile and is idempotent in two ways:
 *
 * <ul>
 *   <li>The whole seed is skipped when the catalog already holds a healthy
 *       number of published products (so a populated DB is never re-seeded).</li>
 *   <li>Each product is inserted only when its unique SKU does not already
 *       exist, so a partial seed can be safely completed on a later run.</li>
 * </ul>
 *
 * <p>Every product gets one {@link ProductImage} whose {@code object_key} is the
 * storefront-relative web path of a bundled photo (e.g. {@code products/shifa-01.jpg}).
 * The storefront resolves that key to {@code /products/shifa-01.jpg} from its own
 * {@code public/} folder. Prices use realistic INR values with {@code mrp > salePrice}
 * so a discount badge is shown.
 */
@Component
@Profile("local")
@Order(20)
public class CatalogSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CatalogSeeder.class);

    /** Below this many published products, the seeder tops up the catalog. */
    private static final int MIN_PUBLISHED_PRODUCTS = 8;

    private final ProductRepository productRepository;
    private final ProductImageRepository productImageRepository;
    private final CategoryRepository categoryRepository;

    public CatalogSeeder(ProductRepository productRepository,
                         ProductImageRepository productImageRepository,
                         CategoryRepository categoryRepository) {
        this.productRepository = productRepository;
        this.productImageRepository = productImageRepository;
        this.categoryRepository = categoryRepository;
    }

    @Override
    public void run(String... args) {
        long published = productRepository
                .findByVisibilityOrderByNameAsc(ProductVisibility.PUBLISHED).size();

        if (published < MIN_PUBLISHED_PRODUCTS) {
            int created = 0;
            for (SeedProduct seed : CATALOG) {
                if (productRepository.existsBySku(seed.sku())) {
                    continue; // Idempotent: skip an already-seeded SKU.
                }
                Product product = productRepository.save(new Product(
                        seed.sku(),
                        seed.name(),
                        seed.description(),
                        seed.mrp(),
                        seed.salePrice(),
                        ProductVisibility.PUBLISHED));
                productImageRepository.save(new ProductImage(
                        product.getId(), seed.imageKey(), true, 0));
                created++;
            }
            if (created > 0) {
                log.info("Seeded {} herbal catalog products (local profile).", created);
            }
        }

        assignCatalogDiscoveryFields();
    }

    /**
     * Assigns the Catalog &amp; Discovery fields (category, stock, featured) to
     * the seeded products (Catalog &amp; Discovery). Idempotent: a product is only
     * touched when it still has no category, so a re-run — or a DB seeded before
     * V3 — is topped up exactly once without clobbering admin edits.
     */
    private void assignCatalogDiscoveryFields() {
        int updated = 0;
        for (SeedProduct seed : CATALOG) {
            if (seed.categorySlug() == null) {
                continue;
            }
            Product product = productRepository.findBySku(seed.sku()).orElse(null);
            if (product == null || product.getCategory() != null) {
                continue; // missing, or already categorised — leave as-is
            }
            categoryRepository.findBySlug(seed.categorySlug())
                    .ifPresent(product::setCategory);
            product.setStockQuantity(seed.stockQuantity());
            product.setTrackInventory(seed.trackInventory());
            product.setFeatured(seed.featured());
            productRepository.save(product);
            updated++;
        }
        if (updated > 0) {
            log.info("Assigned catalog category/stock/featured to {} products (local profile).",
                    updated);
        }
    }

    private record SeedProduct(
            String sku,
            String name,
            String description,
            BigDecimal mrp,
            BigDecimal salePrice,
            String imageKey,
            String categorySlug,
            int stockQuantity,
            boolean trackInventory,
            boolean featured) {
    }

    private static BigDecimal inr(String value) {
        return new BigDecimal(value);
    }

    /**
     * Curated herbal/ayurvedic catalog with realistic INR pricing. Each entry
     * also carries a category slug (matching the V3-seeded categories), a stock
     * quantity + inventory-tracking flag, and a featured marker so the storefront
     * showcases stock badges, filters and a featured collection out of the box.
     * The mix intentionally includes low-stock, out-of-stock and untracked
     * (always in-stock) examples.
     */
    private static final List<SeedProduct> CATALOG = List.of(
            new SeedProduct(
                    "SHR-ASHW-60",
                    "Ashwagandha Capsules (60 ct)",
                    "Pure Withania somnifera root extract to help the body adapt to stress, "
                            + "support restful sleep, and sustain natural energy and stamina. "
                            + "Two veggie capsules a day, standardised for withanolides.",
                    inr("799.00"), inr("549.00"), "products/shifa-01.jpg",
                    "immunity", 40, true, true),
            new SeedProduct(
                    "SHR-TRIP-200",
                    "Triphala Churna (200 g)",
                    "A classic blend of Amla, Haritaki and Bibhitaki that gently supports "
                            + "digestion, regularity and natural detoxification. Take a teaspoon "
                            + "with warm water before bed.",
                    inr("349.00"), inr("249.00"), "products/shifa-02.jpg",
                    "churna", 25, true, false),
            new SeedProduct(
                    "SHR-BRAH-100",
                    "Brahmi Hair Oil (100 ml)",
                    "Cold-infused Brahmi and Bhringraj in a coconut-sesame base to nourish the "
                            + "scalp, reduce hair fall and promote thicker, shinier hair. Massage "
                            + "twice a week for best results.",
                    inr("399.00"), inr("299.00"), "products/shifa-03.jpg",
                    "hair-and-skin", 3, true, true),
            new SeedProduct(
                    "SHR-GILO-500",
                    "Giloy Juice (500 ml)",
                    "Fresh-pressed Giloy (Guduchi) juice, a time-honoured immunity builder that "
                            + "supports the body's natural defences and healthy metabolism. Dilute "
                            + "30 ml in water each morning.",
                    inr("299.00"), inr("219.00"), "products/shifa-04.jpg",
                    "juices", 60, true, false),
            new SeedProduct(
                    "SHR-CHYA-500",
                    "Chyawanprash (500 g)",
                    "A rich Amla-based rejuvenating jam with over 40 herbs and pure cow ghee to "
                            + "strengthen immunity, vitality and respiratory health. Enjoy a spoon "
                            + "daily with warm milk.",
                    inr("545.00"), inr("399.00"), "products/shifa-05.jpg",
                    "immunity", 18, true, true),
            new SeedProduct(
                    "SHR-NEEM-150",
                    "Neem & Tulsi Face Wash (150 ml)",
                    "A gentle sulphate-free cleanser with Neem and Tulsi that clears excess oil, "
                            + "fights blemishes and leaves skin fresh and balanced. Suitable for "
                            + "daily use on oily and acne-prone skin.",
                    inr("299.00"), inr("199.00"), "products/shifa-06.jpg",
                    "personal-care", 0, true, false),
            new SeedProduct(
                    "SHR-AMLA-500",
                    "Amla Juice (500 ml)",
                    "Cold-pressed Indian Gooseberry juice packed with natural Vitamin C to "
                            + "support immunity, glowing skin and healthy hair. Take 30 ml on an "
                            + "empty stomach each morning.",
                    inr("279.00"), inr("199.00"), "products/shifa-07.jpg",
                    "juices", 0, false, false),
            new SeedProduct(
                    "SHR-SHIL-20",
                    "Shilajit Resin (20 g)",
                    "Purified Himalayan Shilajit resin rich in fulvic acid and trace minerals to "
                            + "support stamina, strength and vitality. Dissolve a pea-sized portion "
                            + "in warm milk or water.",
                    inr("1299.00"), inr("999.00"), "products/shifa-08.jpg",
                    "immunity", 12, true, true),
            new SeedProduct(
                    "SHR-KADH-100",
                    "Herbal Immunity Kadha (100 g)",
                    "A ready-to-brew blend of Tulsi, Ginger, Mulethi, Cinnamon and black pepper "
                            + "for a warming daily immunity drink. Simmer a teaspoon in water and "
                            + "sip warm.",
                    inr("349.00"), inr("259.00"), "products/shifa-09.jpg",
                    "immunity", 30, true, false),
            new SeedProduct(
                    "SHR-ALOE-200",
                    "Aloe Vera Gel (200 ml)",
                    "Multipurpose Aloe Vera gel to soothe, hydrate and calm skin and hair. Light, "
                            + "non-sticky and free from parabens and artificial colour. Use on face, "
                            + "body and scalp.",
                    inr("299.00"), inr("199.00"), "products/shifa-10.jpg",
                    "personal-care", 5, true, false),
            new SeedProduct(
                    "SHR-MORI-100",
                    "Moringa Powder (100 g)",
                    "Nutrient-dense Moringa leaf powder, a natural source of plant protein, iron "
                            + "and antioxidants to support daily wellness and energy. Blend a "
                            + "teaspoon into smoothies or warm water.",
                    inr("399.00"), inr("289.00"), "products/shifa-11.jpg",
                    "digestion", 22, true, false),
            new SeedProduct(
                    "SHR-KARE-500",
                    "Karela Jamun Juice (500 ml)",
                    "A bitter-gourd and jamun blend traditionally used to support healthy blood "
                            + "sugar levels and metabolism. Take 30 ml diluted in water before "
                            + "meals.",
                    inr("299.00"), inr("225.00"), "products/shifa-12.jpg",
                    "juices", 0, false, false));
}
