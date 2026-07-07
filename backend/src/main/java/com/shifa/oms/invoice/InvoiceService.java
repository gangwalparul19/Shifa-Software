package com.shifa.oms.invoice;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.SalespersonScopeResolver;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderLineItem;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.product.Product;
import com.shifa.oms.product.ProductRepository;
import com.shifa.oms.settings.AppSettings;
import com.shifa.oms.settings.SettingsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Invoice application service: renders a professional per-order PDF invoice on
 * demand.
 *
 * <p><strong>Design — content vs rendering.</strong> Invoice generation is split
 * into a pure {@link InvoiceContentBuilder} that assembles the
 * {@link InvoiceContent} model and an {@link InvoicePdfRenderer} that turns the
 * model into PDF bytes with OpenPDF, mirroring the label module. This lets the
 * invoice content be unit-tested without parsing PDF bytes.
 *
 * <p><strong>On-demand, no storage.</strong> The PDF is generated fresh from the
 * current order state for each request; it is intentionally not persisted (the
 * {@code StorageService} could archive it later if needed, but that is not
 * required).
 *
 * <p><strong>Two access paths.</strong>
 * <ul>
 *   <li>{@link #invoicePdf(Long, AuthPrincipal)} — authenticated and
 *       role-scoped. It applies the <em>same</em> salesperson scoping rule as
 *       {@code OrderService.getOrder} via {@link SalespersonScopeResolver}: a
 *       salesperson only resolves their own orders ({@code findByIdAndCreatedBy})
 *       while admins/accountants resolve any order ({@code findById}); an unknown
 *       or out-of-scope id yields a 404 (Req 5.5, 5.4, 21.1).</li>
 *   <li>{@link #invoicePdfByOrderCode(String)} — public, mirroring the existing
 *       public {@code GET /api/track/{orderCode}} tracking endpoint: the
 *       storefront customer fetches their invoice by order code without
 *       authentication. Order codes are the same unguessable-ish identifiers the
 *       public tracking endpoint already exposes by, so this keeps the storefront
 *       simple and consistent; an unknown code yields a 404.</li>
 * </ul>
 *
 * <p>Depending on {@link OrderRepository} + {@link SalespersonScopeResolver}
 * (rather than {@code OrderService}) keeps the scoping rule identical while
 * leaving the service unit-testable with mocked collaborators.
 */
@Service
public class InvoiceService {

    private final OrderRepository orderRepository;
    private final SalespersonScopeResolver scopeResolver;
    private final SettingsService settingsService;
    private final ProductRepository productRepository;
    private final com.shifa.oms.settings.CompanyLogoService companyLogoService;
    private final InvoiceNumberService invoiceNumberService;
    private final InvoiceContentBuilder contentBuilder;
    private final InvoicePdfRenderer pdfRenderer;

    /**
     * Test-friendly constructor without the company-logo / invoice-number
     * collaborators; invoices render with the text brand (no logo) and the
     * invoice number falls back to the order code. Used by unit tests.
     */
    public InvoiceService(OrderRepository orderRepository, SalespersonScopeResolver scopeResolver,
                          SettingsService settingsService, ProductRepository productRepository) {
        this(orderRepository, scopeResolver, settingsService, productRepository, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public InvoiceService(OrderRepository orderRepository, SalespersonScopeResolver scopeResolver,
                          SettingsService settingsService, ProductRepository productRepository,
                          com.shifa.oms.settings.CompanyLogoService companyLogoService,
                          InvoiceNumberService invoiceNumberService) {
        this.orderRepository = orderRepository;
        this.scopeResolver = scopeResolver;
        this.settingsService = settingsService;
        this.productRepository = productRepository;
        this.companyLogoService = companyLogoService;
        this.invoiceNumberService = invoiceNumberService;
        this.contentBuilder = new InvoiceContentBuilder();
        this.pdfRenderer = new InvoicePdfRenderer();
    }

    /**
     * Renders the invoice for an order id, enforcing salesperson scoping (Req
     * 5.5, 21.1). Returns a 404 for an unknown or out-of-scope order.
     *
     * @param id    the order id
     * @param actor the acting principal
     * @return the rendered invoice (PDF bytes + order code for the filename)
     */
    @Transactional
    public InvoiceDocument invoicePdf(Long id, AuthPrincipal actor) {
        Optional<Long> constraint = scopeResolver.creatorConstraint(actor);
        OrderEntity order = constraint
                .map(createdBy -> orderRepository.findByIdAndCreatedBy(id, createdBy))
                .orElseGet(() -> orderRepository.findById(id))
                .orElseThrow(() -> new ResourceNotFoundException("Order " + id + " does not exist."));
        return render(order);
    }

    /**
     * Renders the invoice for an order by its code, for the public storefront
     * tracking path. Returns a 404 for an unknown code.
     *
     * @param orderCode the order code
     * @return the rendered invoice (PDF bytes + order code for the filename)
     */
    @Transactional
    public InvoiceDocument invoicePdfByOrderCode(String orderCode) {
        OrderEntity order = orderRepository.findByOrderCode(orderCode)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No order found for code " + orderCode + "."));
        return render(order);
    }

    private InvoiceDocument render(OrderEntity order) {
        AppSettings settings = settingsService.getSettings();
        // Allocate + persist a stable invoice number on first generation (Feature
        // 1); re-downloads reuse it. Falls back to the order code when the
        // allocator is not wired (unit tests using the test-friendly constructor).
        String invoiceNumber = (invoiceNumberService != null && order.getId() != null)
                ? invoiceNumberService.assignIfAbsent(order.getId())
                : order.getOrderCode();
        InvoiceContent content;
        if (settings.isGstEnabled()) {
            Map<Long, Product> products = productsByLineItem(order);
            content = contentBuilder.build(order, settings, invoiceNumber,
                    hsnByProductId(products), gstRateByProductId(products));
        } else {
            content = contentBuilder.build(order, settings, invoiceNumber, Map.of(), Map.of());
        }
        byte[] logo = companyLogoService != null
                ? companyLogoService.currentLogoPng().orElse(null)
                : null;
        byte[] pdf = pdfRenderer.render(content, logo);
        return new InvoiceDocument(order.getOrderCode(), pdf);
    }

    /** Loads the products referenced by an order's line items, keyed by product id. */
    private Map<Long, Product> productsByLineItem(OrderEntity order) {
        Set<Long> productIds = new HashSet<>();
        for (OrderLineItem line : order.getLineItems()) {
            if (line.getProductId() != null) {
                productIds.add(line.getProductId());
            }
        }
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Product> byId = new HashMap<>();
        for (Product product : productRepository.findAllById(productIds)) {
            byId.put(product.getId(), product);
        }
        return byId;
    }

    /**
     * Per-line HSN codes for GST tax invoices. Products without an HSN are absent
     * from the map, yielding a blank HSN on the invoice.
     */
    private Map<Long, String> hsnByProductId(Map<Long, Product> products) {
        Map<Long, String> hsn = new HashMap<>();
        for (Product product : products.values()) {
            if (product.getHsnCode() != null && !product.getHsnCode().isBlank()) {
                hsn.put(product.getId(), product.getHsnCode());
            }
        }
        return hsn;
    }

    /**
     * Per-product GST rate percents for GST tax invoices. Products without a rate
     * are absent from the map, so the builder falls back to the settings default.
     */
    private Map<Long, java.math.BigDecimal> gstRateByProductId(Map<Long, Product> products) {
        Map<Long, java.math.BigDecimal> rates = new HashMap<>();
        for (Product product : products.values()) {
            if (product.getGstRate() != null) {
                rates.put(product.getId(), product.getGstRate());
            }
        }
        return rates;
    }

    /**
     * A rendered invoice: the order code (used to build the download filename
     * {@code invoice-<orderCode>.pdf}) and the PDF bytes.
     *
     * @param orderCode the order code
     * @param content   the PDF bytes
     */
    public record InvoiceDocument(String orderCode, byte[] content) {
        /** The suggested inline/download filename for this invoice. */
        public String filename() {
            return "invoice-" + orderCode + ".pdf";
        }
    }
}
