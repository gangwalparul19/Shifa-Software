package com.shifa.oms.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A catalog product, mapped to the existing {@code products} table (see the V1
 * Flyway migration). SKU uniqueness is enforced both by the DB unique
 * constraint and by a pre-insert check in the service layer (Req 6.1, 6.2, 6.3).
 *
 * <p>Monetary columns ({@link #mrp}, {@link #salePrice}) are {@code DECIMAL(12,2)}
 * and carried as {@link BigDecimal} to avoid floating-point drift. The
 * {@link #visibility} flag controls storefront exposure (Req 6.4, 1.1).
 */
@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sku", nullable = false, unique = true, length = 64)
    private String sku;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "mrp", nullable = false, precision = 12, scale = 2)
    private BigDecimal mrp = BigDecimal.ZERO;

    @Column(name = "sale_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal salePrice = BigDecimal.ZERO;

    /**
     * Optional per-product MINIMUM sale price — the floor a salesperson may charge
     * on order entry (price-list feature). {@code null} means "no minimum" (legacy
     * products), in which case the order-entry band is not enforced. When set, the
     * effective band is {@code [min_price, mrp]} with {@link #salePrice} as the
     * auto-fetched default.
     */
    @Column(name = "min_price", precision = 12, scale = 2)
    private BigDecimal minPrice;

    /** Optional HSN code, surfaced on GST tax invoices when present (Req: GST). */
    @Column(name = "hsn_code", length = 20)
    private String hsnCode;

    /**
     * Optional pack size / net weight label from the price list (e.g. {@code 100ML},
     * {@code 350gm}, {@code 60 Pc cpsl}, {@code Combo}); display-only.
     */
    @Column(name = "pack_size", length = 40)
    private String packSize;

    /**
     * Optional per-product GST rate percent (e.g. 5.00 / 12.00 / 18.00) used on
     * GST tax invoices when present; {@code null} falls back to the settings-level
     * default GST rate, keeping existing products backward compatible.
     */
    @Column(name = "gst_rate", precision = 5, scale = 2)
    private BigDecimal gstRate;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 10)
    private ProductVisibility visibility = ProductVisibility.HIDDEN;

    /**
     * Optional category / collection this product belongs to (Catalog &
     * Discovery). Nullable so a product may be uncategorised. Fetched lazily; the
     * response projection reads id/slug/name inside the read-only transaction.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    /** On-hand units; only meaningful when {@link #trackInventory} is true. */
    @Column(name = "stock_quantity", nullable = false)
    private int stockQuantity = 0;

    /**
     * When false the product opts out of stock tracking and always reads as
     * in-stock; when true the derived {@link StockStatus} depends on
     * {@link #stockQuantity}. Defaults to false to preserve legacy behaviour.
     */
    @Column(name = "track_inventory", nullable = false)
    private boolean trackInventory = false;

    /**
     * Optional per-product low-stock threshold override; {@code null} falls back
     * to the settings-level default ({@code app_settings.low_stock_threshold}).
     * Only meaningful when {@link #trackInventory} is true.
     */
    @Column(name = "low_stock_threshold")
    private Integer lowStockThreshold;

    /** Marks the product for the storefront "featured collection". */
    @Column(name = "featured", nullable = false)
    private boolean featured = false;

    /**
     * Optional per-product dead weight in grams (V49), used to compute the parcel
     * weight sent to QuikShipX as {@code SUM(weight * line quantity)} across the
     * order. {@code null} falls back to the settings-level default
     * ({@code app_settings.ship_dead_weight_grams}), keeping existing products
     * backward compatible (Req 16.7).
     */
    @Column(name = "dead_weight_grams")
    private Integer deadWeightGrams;

    /**
     * Product images, read-only from the product aggregate. Ordered by
     * {@code sort_order} so the primary image is first (Req 1.4).
     */
    @OneToMany(fetch = jakarta.persistence.FetchType.LAZY)
    @JoinColumn(name = "product_id", insertable = false, updatable = false)
    @OrderBy("sortOrder ASC")
    private List<ProductImage> images = new ArrayList<>();

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    protected Product() {
        // Required by JPA.
    }

    public Product(String sku, String name, String description, BigDecimal mrp, BigDecimal salePrice,
                   ProductVisibility visibility) {
        this.sku = sku;
        this.name = name;
        this.description = description;
        this.mrp = mrp;
        this.salePrice = salePrice;
        this.visibility = visibility;
    }

    public Long getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getMrp() {
        return mrp;
    }

    public void setMrp(BigDecimal mrp) {
        this.mrp = mrp;
    }

    public BigDecimal getSalePrice() {
        return salePrice;
    }

    public void setSalePrice(BigDecimal salePrice) {
        this.salePrice = salePrice;
    }

    public BigDecimal getMinPrice() {
        return minPrice;
    }

    public void setMinPrice(BigDecimal minPrice) {
        this.minPrice = minPrice;
    }

    public String getHsnCode() {
        return hsnCode;
    }

    public void setHsnCode(String hsnCode) {
        this.hsnCode = hsnCode;
    }

    public String getPackSize() {
        return packSize;
    }

    public void setPackSize(String packSize) {
        this.packSize = packSize;
    }

    public BigDecimal getGstRate() {
        return gstRate;
    }

    public void setGstRate(BigDecimal gstRate) {
        this.gstRate = gstRate;
    }

    public ProductVisibility getVisibility() {
        return visibility;
    }

    public void setVisibility(ProductVisibility visibility) {
        this.visibility = visibility;
    }

    public boolean isPublished() {
        return visibility == ProductVisibility.PUBLISHED;
    }

    public Category getCategory() {
        return category;
    }

    public void setCategory(Category category) {
        this.category = category;
    }

    public int getStockQuantity() {
        return stockQuantity;
    }

    public void setStockQuantity(int stockQuantity) {
        this.stockQuantity = stockQuantity;
    }

    public boolean isTrackInventory() {
        return trackInventory;
    }

    public void setTrackInventory(boolean trackInventory) {
        this.trackInventory = trackInventory;
    }

    public Integer getLowStockThreshold() {
        return lowStockThreshold;
    }

    public void setLowStockThreshold(Integer lowStockThreshold) {
        this.lowStockThreshold = lowStockThreshold;
    }

    public boolean isFeatured() {
        return featured;
    }

    public void setFeatured(boolean featured) {
        this.featured = featured;
    }

    public Integer getDeadWeightGrams() {
        return deadWeightGrams;
    }

    public void setDeadWeightGrams(Integer deadWeightGrams) {
        this.deadWeightGrams = deadWeightGrams;
    }

    /** Derived stock status from the inventory flag + on-hand quantity. */
    public StockStatus stockStatus() {
        return StockStatus.of(trackInventory, stockQuantity);
    }

    /** Whether the product is currently purchasable (not out of stock). */
    public boolean isInStock() {
        return stockStatus().isPurchasable();
    }

    public List<ProductImage> getImages() {
        return images;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
