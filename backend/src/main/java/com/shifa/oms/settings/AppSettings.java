package com.shifa.oms.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Single-row application settings, mapped to the {@code app_settings} table
 * (V2 migration). Carries the company identity plus the GST configuration that
 * controls whether invoices render as a plain {@code INVOICE} or a GST
 * {@code TAX INVOICE}.
 *
 * <p>There is always exactly one logical row (id = 1); {@link SettingsService}
 * loads it and creates it on demand with defaults if it is somehow missing.
 * {@code created_at}/{@code updated_at} are filled by DB defaults so they are
 * not written on insert/update, matching the {@code Product}/{@code OrderEntity}
 * conventions. Monetary/rate values are {@link BigDecimal}.
 */
@Entity
@Table(name = "app_settings")
public class AppSettings {

    /** The fixed id of the single settings row. */
    public static final long SINGLETON_ID = 1L;

    @Id
    @Column(name = "id", nullable = false)
    private Long id = SINGLETON_ID;

    @Column(name = "gst_enabled", nullable = false)
    private boolean gstEnabled = false;

    @Column(name = "gstin", length = 20)
    private String gstin;

    @Column(name = "legal_name", nullable = false, length = 200)
    private String legalName = "Shifa Herbal Remedies";

    @Column(name = "address_line", length = 250)
    private String addressLine;

    @Column(name = "city", length = 100)
    private String city;

    @Column(name = "state", length = 100)
    private String state;

    @Column(name = "state_code", length = 4)
    private String stateCode;

    @Column(name = "gst_rate_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal gstRatePercent = new BigDecimal("5.00");

    @Column(name = "prices_include_gst", nullable = false)
    private boolean pricesIncludeGst = true;

    @Column(name = "invoice_footer_note", length = 500)
    private String invoiceFooterNote;

    @Column(name = "contact_phone", length = 20)
    private String contactPhone;

    @Column(name = "contact_email", length = 120)
    private String contactEmail;

    /** Opaque storage key of the uploaded company logo; null means no logo configured. */
    @Column(name = "logo_object_key", length = 255)
    private String logoObjectKey;

    /**
     * Settings-level default low-stock threshold. A tracked product is "low
     * stock" when {@code 0 < stockQuantity <= threshold}; a per-product override
     * on the product wins when present.
     */
    @Column(name = "low_stock_threshold", nullable = false)
    private int lowStockThreshold = 5;

    /**
     * Optional invoice-number prefix (e.g. {@code "SHR/24-25/"}). The running
     * series number is allocated from {@code invoice_sequence} and formatted as
     * {@code <prefix><zero-padded number>}; a blank prefix yields just the number.
     */
    @Column(name = "invoice_number_prefix", length = 40)
    private String invoiceNumberPrefix;

    /** Optional multi-line terms &amp; conditions rendered on the invoice. */
    @Column(name = "invoice_terms", length = 2000)
    private String invoiceTerms;

    /** Optional bank name rendered on the invoice when present. */
    @Column(name = "bank_name", length = 120)
    private String bankName;

    /** Optional bank account holder name. */
    @Column(name = "bank_account_name", length = 120)
    private String bankAccountName;

    /** Optional bank account number. */
    @Column(name = "bank_account_number", length = 40)
    private String bankAccountNumber;

    /** Optional bank IFSC code. */
    @Column(name = "bank_ifsc", length = 20)
    private String bankIfsc;

    /** Optional bank branch. */
    @Column(name = "bank_branch", length = 120)
    private String bankBranch;

    /**
     * Configurable set of GST rates the business uses, as a comma-separated list
     * (e.g. {@code "0,5,12,18,28"}). Exposed via settings so the product form /
     * order entry can offer them; the single {@link #gstRatePercent} default keeps
     * working independently.
     */
    @Column(name = "gst_slabs", length = 100)
    private String gstSlabs;

    // ------------------------------------------------------------------
    // Shipment defaults (V49). The QuikShipX create-order body requires parcel
    // logistics Shifa OMS has never stored per order; these supply them once so a
    // salesperson never types shipping details. See docs/QUIKSHIPX-API-V1.md.
    // ------------------------------------------------------------------

    /**
     * QuikShipX pickup warehouse id, taken from the QuikShipX dashboard.
     * Deliberately nullable and unset by default: publication is blocked with an
     * admin alert until it is configured, rather than sending a body QuikShipX
     * would reject (Req 16.6).
     */
    @Column(name = "ship_pickup_warehouse_id", length = 40)
    private String shipPickupWarehouseId;

    /** QuikShipX package type: {@code "1"} flyer, {@code "2"} cardboard (Req 16.4). */
    @Column(name = "ship_package_type", nullable = false, length = 1)
    private String shipPackageType = "1";

    /** QuikShipX shipping mode: {@code "1"} surface, {@code "2"} express (Req 16.4). */
    @Column(name = "ship_shipping_mode", nullable = false, length = 1)
    private String shipShippingMode = "1";

    /**
     * Default parcel dead weight in grams, used when no product on the order
     * carries its own weight (Req 16.7).
     */
    @Column(name = "ship_dead_weight_grams", nullable = false)
    private int shipDeadWeightGrams = 500;

    @Column(name = "ship_length_cm", nullable = false)
    private int shipLengthCm = 10;

    @Column(name = "ship_width_cm", nullable = false)
    private int shipWidthCm = 10;

    @Column(name = "ship_height_cm", nullable = false)
    private int shipHeightCm = 10;

    /** Delivery charge levied by the seller; zero when delivery is free. */
    @Column(name = "ship_shipping_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal shipShippingAmount = BigDecimal.ZERO;

    /** Fallback {@code product_category} for a product with no category. */
    @Column(name = "ship_default_category", length = 120)
    private String shipDefaultCategory;

    /**
     * Fallback HSN code (V50) for a product line with no HSN. QuikShipX rejects an HSN
     * shorter than 2 characters, and many products are not yet HSN-coded, so this stands in
     * when neither the order line nor the product carries one.
     */
    @Column(name = "ship_default_hsn", length = 20)
    private String shipDefaultHsn;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    public AppSettings() {
        // Required by JPA.
    }

    /** Builds a fresh defaults row (GST disabled) for create-on-missing. */
    public static AppSettings defaults() {
        AppSettings settings = new AppSettings();
        settings.id = SINGLETON_ID;
        settings.gstEnabled = false;
        settings.legalName = "Shifa Herbal Remedies";
        settings.gstRatePercent = new BigDecimal("5.00");
        settings.pricesIncludeGst = true;
        settings.invoiceNumberPrefix = "SHR/";
        settings.gstSlabs = "0,5,12,18,28";
        return settings;
    }

    public Long getId() {
        return id;
    }

    public boolean isGstEnabled() {
        return gstEnabled;
    }

    public void setGstEnabled(boolean gstEnabled) {
        this.gstEnabled = gstEnabled;
    }

    public String getGstin() {
        return gstin;
    }

    public void setGstin(String gstin) {
        this.gstin = gstin;
    }

    public String getLegalName() {
        return legalName;
    }

    public void setLegalName(String legalName) {
        this.legalName = legalName;
    }

    public String getAddressLine() {
        return addressLine;
    }

    public void setAddressLine(String addressLine) {
        this.addressLine = addressLine;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getStateCode() {
        return stateCode;
    }

    public void setStateCode(String stateCode) {
        this.stateCode = stateCode;
    }

    public BigDecimal getGstRatePercent() {
        return gstRatePercent;
    }

    public void setGstRatePercent(BigDecimal gstRatePercent) {
        this.gstRatePercent = gstRatePercent;
    }

    public boolean isPricesIncludeGst() {
        return pricesIncludeGst;
    }

    public void setPricesIncludeGst(boolean pricesIncludeGst) {
        this.pricesIncludeGst = pricesIncludeGst;
    }

    public String getInvoiceFooterNote() {
        return invoiceFooterNote;
    }

    public void setInvoiceFooterNote(String invoiceFooterNote) {
        this.invoiceFooterNote = invoiceFooterNote;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public void setContactPhone(String contactPhone) {
        this.contactPhone = contactPhone;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public void setContactEmail(String contactEmail) {
        this.contactEmail = contactEmail;
    }

    public String getLogoObjectKey() {
        return logoObjectKey;
    }

    public void setLogoObjectKey(String logoObjectKey) {
        this.logoObjectKey = logoObjectKey;
    }

    public int getLowStockThreshold() {
        return lowStockThreshold;
    }

    public void setLowStockThreshold(int lowStockThreshold) {
        this.lowStockThreshold = lowStockThreshold;
    }

    public String getInvoiceNumberPrefix() {
        return invoiceNumberPrefix;
    }

    public void setInvoiceNumberPrefix(String invoiceNumberPrefix) {
        this.invoiceNumberPrefix = invoiceNumberPrefix;
    }

    public String getInvoiceTerms() {
        return invoiceTerms;
    }

    public void setInvoiceTerms(String invoiceTerms) {
        this.invoiceTerms = invoiceTerms;
    }

    public String getBankName() {
        return bankName;
    }

    public void setBankName(String bankName) {
        this.bankName = bankName;
    }

    public String getBankAccountName() {
        return bankAccountName;
    }

    public void setBankAccountName(String bankAccountName) {
        this.bankAccountName = bankAccountName;
    }

    public String getBankAccountNumber() {
        return bankAccountNumber;
    }

    public void setBankAccountNumber(String bankAccountNumber) {
        this.bankAccountNumber = bankAccountNumber;
    }

    public String getBankIfsc() {
        return bankIfsc;
    }

    public void setBankIfsc(String bankIfsc) {
        this.bankIfsc = bankIfsc;
    }

    public String getBankBranch() {
        return bankBranch;
    }

    public void setBankBranch(String bankBranch) {
        this.bankBranch = bankBranch;
    }

    public String getGstSlabs() {
        return gstSlabs;
    }

    public void setGstSlabs(String gstSlabs) {
        this.gstSlabs = gstSlabs;
    }

    public String getShipPickupWarehouseId() {
        return shipPickupWarehouseId;
    }

    public void setShipPickupWarehouseId(String shipPickupWarehouseId) {
        this.shipPickupWarehouseId = shipPickupWarehouseId;
    }

    public String getShipPackageType() {
        return shipPackageType;
    }

    public void setShipPackageType(String shipPackageType) {
        this.shipPackageType = shipPackageType;
    }

    public String getShipShippingMode() {
        return shipShippingMode;
    }

    public void setShipShippingMode(String shipShippingMode) {
        this.shipShippingMode = shipShippingMode;
    }

    public int getShipDeadWeightGrams() {
        return shipDeadWeightGrams;
    }

    public void setShipDeadWeightGrams(int shipDeadWeightGrams) {
        this.shipDeadWeightGrams = shipDeadWeightGrams;
    }

    public int getShipLengthCm() {
        return shipLengthCm;
    }

    public void setShipLengthCm(int shipLengthCm) {
        this.shipLengthCm = shipLengthCm;
    }

    public int getShipWidthCm() {
        return shipWidthCm;
    }

    public void setShipWidthCm(int shipWidthCm) {
        this.shipWidthCm = shipWidthCm;
    }

    public int getShipHeightCm() {
        return shipHeightCm;
    }

    public void setShipHeightCm(int shipHeightCm) {
        this.shipHeightCm = shipHeightCm;
    }

    public BigDecimal getShipShippingAmount() {
        return shipShippingAmount;
    }

    public void setShipShippingAmount(BigDecimal shipShippingAmount) {
        this.shipShippingAmount = shipShippingAmount;
    }

    public String getShipDefaultCategory() {
        return shipDefaultCategory;
    }

    public void setShipDefaultCategory(String shipDefaultCategory) {
        this.shipDefaultCategory = shipDefaultCategory;
    }

    public String getShipDefaultHsn() {
        return shipDefaultHsn;
    }

    public void setShipDefaultHsn(String shipDefaultHsn) {
        this.shipDefaultHsn = shipDefaultHsn;
    }

    /**
     * Whether the shipment defaults are complete enough to publish an order.
     * Only the pickup warehouse id has no safe fallback, so it alone gates
     * publication (Req 16.2, 16.6).
     */
    public boolean shipmentDefaultsComplete() {
        return shipPickupWarehouseId != null && !shipPickupWarehouseId.isBlank();
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
