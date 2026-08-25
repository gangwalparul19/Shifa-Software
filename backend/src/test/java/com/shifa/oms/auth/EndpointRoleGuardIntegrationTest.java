package com.shifa.oms.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.common.PageResponse;
import com.shifa.oms.dashboard.RoleDashboardController;
import com.shifa.oms.dashboard.RoleDashboardService;
import com.shifa.oms.dashboard.dto.RoleDashboardSummary;
import com.shifa.oms.label.LabelService;
import com.shifa.oms.order.AdminOrderController;
import com.shifa.oms.order.AdminOrderService;
import com.shifa.oms.order.BulkOrderService;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.dto.OrderResponse;
import com.shifa.oms.crm.CustomerController;
import com.shifa.oms.crm.CustomerService;
import com.shifa.oms.crm.dto.CustomerDetailResponse;
import com.shifa.oms.crm.dto.CustomerSummaryResponse;
import com.shifa.oms.gst.GstAccountingService;
import com.shifa.oms.gst.GstController;
import com.shifa.oms.gst.GstPdfExporter;
import com.shifa.oms.gst.Gstr1Exporter;
import com.shifa.oms.gst.Gstr1ReturnService;
import com.shifa.oms.gst.domain.B2bRow;
import com.shifa.oms.gst.domain.B2csRow;
import com.shifa.oms.gst.domain.DocRow;
import com.shifa.oms.gst.domain.GstEngine.Gstr3bSummary;
import com.shifa.oms.gst.domain.Gstr1Return;
import com.shifa.oms.gst.domain.HsnRow;
import com.shifa.oms.gst.domain.SupplyType;
import com.shifa.oms.packing.PackingController;
import com.shifa.oms.packing.PackingService;
import com.shifa.oms.packing.dto.PackingScanResponse;
import com.shifa.oms.platform.storage.StorageService;
import com.shifa.oms.product.AdminProductController;
import com.shifa.oms.product.ProductService;
import com.shifa.oms.product.ProductVisibility;
import com.shifa.oms.product.StockStatus;
import com.shifa.oms.product.dto.ProductRequest;
import com.shifa.oms.product.dto.ProductResponse;
import com.shifa.oms.product.dto.ProductSalesStatsResponse;
import com.shifa.oms.reporting.CsvReportExporter;
import com.shifa.oms.reporting.ExcelReportExporter;
import com.shifa.oms.reporting.PdfReportExporter;
import com.shifa.oms.reporting.ReportController;
import com.shifa.oms.reporting.ReportService;
import com.shifa.oms.reporting.domain.ReportType;
import com.shifa.oms.reporting.dto.ReportResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Endpoint role-guard integration test (task 11.2, design §10.3 "Endpoint role
 * guards"; Req 1.6, 2.7).
 *
 * <p>Drives the real controllers through {@link MockMvc} with method security
 * ({@code @PreAuthorize}) active, and asserts the backend enforces the role
 * matrix <strong>independently of the Angular route guards</strong>: for each of
 * the four operational roles a call either succeeds (2xx) or is rejected with
 * {@code 403 Forbidden} exactly as the permission matrix dictates (design §6).
 *
 * <p>The HTTP filter chain here is deliberately permit-all so that the ONLY
 * authorization decision under test is the method-level {@code @PreAuthorize}
 * on each endpoint. Callers are authenticated with a real {@link AuthPrincipal}
 * (as the JWT filter would produce in production) carrying the role's
 * {@code ROLE_<name>} authority. No database is required: the controllers'
 * service collaborators are lightweight recording subclasses (no Mockito mock of
 * a concrete class, per the Java 25 runtime gotcha), and a permitted call only
 * needs to pass the guard — the stubbed service returns a canned result.
 */
@WebMvcTest(controllers = {
        AdminOrderController.class,
        PackingController.class,
        RoleDashboardController.class,
        ReportController.class,
        AdminProductController.class,
        CustomerController.class,
        GstController.class},
        // The production JWT filter (a Filter @Component pulled into the web slice) needs
        // JwtService, which is irrelevant here — callers are authenticated directly via the
        // security-test post-processor. Exclude it so the slice does not wire its dependency chain.
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class))
@Import(EndpointRoleGuardIntegrationTest.TestSecurityAndStubs.class)
class EndpointRoleGuardIntegrationTest {

    /** The four operational roles the workflow authorizes against (Req 2.1). */
    private static final List<Role> OPERATIONAL_ROLES = List.of(
            Role.ADMIN, Role.SALESPERSON, Role.PACKING_USER, Role.ACCOUNTANT);

    @Autowired
    private MockMvc mvc;

    // --- Approve / reject: ADMIN only (design §6.2, class-level hasRole('ADMIN')) ------

    @Test
    void approveIsAdminOnly() throws Exception {
        assertRoleMatrix(post("/api/admin/orders/5/approve"), List.of(Role.ADMIN));
    }

    @Test
    void rejectIsAdminOnly() throws Exception {
        assertRoleMatrix(
                post("/api/admin/orders/5/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Out of stock\"}"),
                List.of(Role.ADMIN));
    }

    // --- Pack / handover / dispatch: PACKING_USER + ADMIN (design §6.3–6.5) ------------

    @Test
    void scanIsPackerOrAdmin() throws Exception {
        assertRoleMatrix(
                post("/api/packing/scan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"barcode\":\"SHR-TEST01\"}"),
                List.of(Role.PACKING_USER, Role.ADMIN));
    }

    @Test
    void handoverIsPackerOrAdmin() throws Exception {
        assertRoleMatrix(post("/api/packing/5/handover"),
                List.of(Role.PACKING_USER, Role.ADMIN));
    }

    @Test
    void dispatchIsPackerOrAdmin() throws Exception {
        assertRoleMatrix(post("/api/packing/5/dispatch"),
                List.of(Role.PACKING_USER, Role.ADMIN));
    }

    // --- Dashboard summary: all four operational roles (design §6.7) -------------------

    @Test
    void dashboardSummaryIsOpenToAllOperationalRoles() throws Exception {
        assertRoleMatrix(get("/api/dashboard/summary"),
                List.of(Role.ADMIN, Role.SALESPERSON, Role.PACKING_USER, Role.ACCOUNTANT));
    }

    // --- Reports: ADMIN + ACCOUNTANT + SALESPERSON (salesperson scoped, design §6.8) ---

    @Test
    void reportIsAdminAccountantOrSalesperson() throws Exception {
        assertRoleMatrix(get("/api/reports/daily"),
                List.of(Role.ADMIN, Role.ACCOUNTANT, Role.SALESPERSON));
    }

    // --- Products: reads ADMIN + SALESPERSON, writes ADMIN-only (this feature) ---------

    /** A minimal valid product payload so bean-validation passes and the ONLY
     * decision under test on writes is the {@code @PreAuthorize} guard. */
    private static final String VALID_PRODUCT_JSON = """
            {"sku":"SHR-GUARD","name":"Guard Test","mrp":"100.00","salePrice":"90.00","visibility":"PUBLISHED"}
            """;

    @Test
    void productListReadIsAdminOrSalesperson() throws Exception {
        assertRoleMatrix(get("/api/admin/products"),
                List.of(Role.ADMIN, Role.SALESPERSON));
    }

    @Test
    void productPageReadIsAdminOrSalesperson() throws Exception {
        assertRoleMatrix(get("/api/admin/products/page"),
                List.of(Role.ADMIN, Role.SALESPERSON));
    }

    @Test
    void productDetailReadIsAdminOrSalesperson() throws Exception {
        assertRoleMatrix(get("/api/admin/products/5"),
                List.of(Role.ADMIN, Role.SALESPERSON));
    }

    @Test
    void productStatsReadIsAdminOrSalesperson() throws Exception {
        assertRoleMatrix(get("/api/admin/products/5/stats"),
                List.of(Role.ADMIN, Role.SALESPERSON));
    }

    @Test
    void productCreateIsAdminOnly() throws Exception {
        assertRoleMatrix(
                post("/api/admin/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PRODUCT_JSON),
                List.of(Role.ADMIN));
    }

    @Test
    void productUpdateIsAdminOnly() throws Exception {
        assertRoleMatrix(
                put("/api/admin/products/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PRODUCT_JSON),
                List.of(Role.ADMIN));
    }

    // --- Customers: reads ADMIN + ACCOUNTANT + SALESPERSON (salesperson scoped) --------

    @Test
    void customerListReadIsAdminAccountantOrSalesperson() throws Exception {
        assertRoleMatrix(get("/api/admin/customers"),
                List.of(Role.ADMIN, Role.ACCOUNTANT, Role.SALESPERSON));
    }

    @Test
    void customerDetailReadIsAdminAccountantOrSalesperson() throws Exception {
        assertRoleMatrix(get("/api/admin/customers/9812345678"),
                List.of(Role.ADMIN, Role.ACCOUNTANT, Role.SALESPERSON));
    }

    // --- GSTR-1 filing endpoints: ADMIN + CA + ACCOUNTANT (GST filing compliance, Req 13.1) ----

    /**
     * The finance/tax role set the GSTR-1 endpoints are guarded against: the three roles allowed by
     * the class-level {@code @PreAuthorize hasAnyRole('ADMIN','CA','ACCOUNTANT')} on {@code GstController},
     * plus the four non-finance roles (SALESPERSON, PACKING_USER, TEAM_LEAD, PAYMENT_VERIFIER) that must
     * be rejected with 403. CA / TEAM_LEAD / PAYMENT_VERIFIER are not in {@link #OPERATIONAL_ROLES}, so
     * these endpoints get their own matrix.
     */
    private static final List<Role> GST_ROLES = List.of(
            Role.ADMIN, Role.CA, Role.ACCOUNTANT,
            Role.SALESPERSON, Role.PACKING_USER, Role.TEAM_LEAD, Role.PAYMENT_VERIFIER);

    @Test
    void gstr1ReturnIsAdminCaOrAccountant() throws Exception {
        assertGstRoleMatrix(get("/api/ca/gst/gstr1"),
                List.of(Role.ADMIN, Role.CA, Role.ACCOUNTANT));
    }

    @Test
    void gstr1ExportIsAdminCaOrAccountant() throws Exception {
        assertGstRoleMatrix(get("/api/ca/gst/gstr1/export"),
                List.of(Role.ADMIN, Role.CA, Role.ACCOUNTANT));
    }

    @Test
    void gstr1JsonExportIsAdminCaOrAccountant() throws Exception {
        assertGstRoleMatrix(get("/api/ca/gst/gstr1/export").param("format", "json"),
                List.of(Role.ADMIN, Role.CA, Role.ACCOUNTANT));
    }

    /**
     * Export fidelity: the taxable-value totals in the {@code /gstr1/export} portal JSON reproduce,
     * section for section, the taxable-value totals of the {@code /gstr1} JSON response — so the export
     * the CA imports into the portal carries exactly the figures shown in the dashboard view (Req 5.3, 5.5).
     */
    @Test
    void gstr1ExportTotalsEqualGstr1ResponseTotals() throws Exception {
        ObjectMapper om = new ObjectMapper();

        String responseBody = mvc.perform(get("/api/ca/gst/gstr1").with(authFor(Role.CA)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String exportBody = mvc.perform(get("/api/ca/gst/gstr1/export").param("format", "json")
                        .with(authFor(Role.CA)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode response = om.readTree(responseBody);
        JsonNode export = om.readTree(exportBody);

        // Each section's taxable total on the response ("taxable") equals the export's ("txval").
        for (String section : List.of("b2b", "b2cs", "hsn")) {
            assertEquals(sumField(response.get(section), "taxable"),
                    sumField(export.get(section), "txval"), 0.001,
                    "section '" + section + "' taxable total must reconcile between /gstr1 and its export");
        }
    }

    /** Sums a decimal {@code field} across a (possibly null/missing) JSON array node. */
    private static double sumField(JsonNode array, String field) {
        double total = 0.0;
        if (array != null && array.isArray()) {
            for (JsonNode row : array) {
                JsonNode value = row.get(field);
                if (value != null) {
                    total += value.asDouble();
                }
            }
        }
        return total;
    }

    // --- Harness ------------------------------------------------------------

    /**
     * Runs {@code request} once per operational role and asserts a 2xx for each
     * role in {@code allowed} and a 403 for every other operational role — i.e.
     * the backend enforces the guard for every role, not just the client.
     */
    private void assertRoleMatrix(MockHttpServletRequestBuilder request, List<Role> allowed)
            throws Exception {
        for (Role role : OPERATIONAL_ROLES) {
            if (allowed.contains(role)) {
                mvc.perform(request.with(authFor(role)))
                        .andExpect(status().is2xxSuccessful());
            } else {
                mvc.perform(request.with(authFor(role)))
                        .andExpect(status().isForbidden());
            }
        }
    }

    /**
     * As {@link #assertRoleMatrix} but over the finance-role set {@link #GST_ROLES} — so the GSTR-1
     * endpoints are asserted allowed for ADMIN/CA/ACCOUNTANT and forbidden (403) for SALESPERSON,
     * PACKING_USER, TEAM_LEAD, and PAYMENT_VERIFIER (Req 13.1).
     */
    private void assertGstRoleMatrix(MockHttpServletRequestBuilder request, List<Role> allowed)
            throws Exception {
        for (Role role : GST_ROLES) {
            if (allowed.contains(role)) {
                mvc.perform(request.with(authFor(role)))
                        .andExpect(status().is2xxSuccessful());
            } else {
                mvc.perform(request.with(authFor(role)))
                        .andExpect(status().isForbidden());
            }
        }
    }

    /** Authenticates the request as a real {@link AuthPrincipal} holding {@code role}. */
    private static RequestPostProcessor authFor(Role role) {
        AuthPrincipal principal = new AuthPrincipal(1L, "user-" + role.name().toLowerCase(), role);
        var token = new UsernamePasswordAuthenticationToken(
                principal, "n/a", List.of(new SimpleGrantedAuthority(role.authority())));
        return authentication(token);
    }

    private static OrderResponse sampleOrderResponse() {
        OrderEntity order = new OrderEntity(
                "SHR-TEST01", OrderSource.STOREFRONT, null,
                "Asha", "9812345678", "12 MG Road", "Pune", "Maharashtra", "411001");
        return OrderResponse.from(order);
    }

    // --- Test wiring: method security + permit-all filter chain + stub services --------

    @TestConfiguration
    @EnableMethodSecurity
    static class TestSecurityAndStubs {

        /**
         * Permit everything at the HTTP layer so authorization is decided solely by
         * the method-level {@code @PreAuthorize} under test; CSRF is disabled so
         * POSTs are not rejected before reaching the guard.
         */
        @Bean
        SecurityFilterChain testFilterChain(HttpSecurity http) throws Exception {
            http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            return http.build();
        }

        @Bean
        CurrentUserService currentUserService() {
            return new CurrentUserService();
        }

        /** Null repo is safe: {@link AuditService#record} is best-effort and never throws. */
        @Bean
        AuditService auditService() {
            return new AuditService(null, new CurrentUserService());
        }

        @Bean
        AdminOrderService adminOrderService() {
            return new StubAdminOrderService();
        }

        @Bean
        SalespersonScopeResolver salespersonScopeResolver() {
            return new SalespersonScopeResolver();
        }

        @Bean
        BulkOrderService bulkOrderService() {
            return new BulkOrderService(null, null, null);
        }

        @Bean
        LabelService labelService() {
            return new LabelService((OrderRepository) null, (StorageService) null);
        }

        @Bean
        PackingService packingService() {
            return new StubPackingService();
        }

        @Bean
        RoleDashboardService roleDashboardService() {
            return new StubRoleDashboardService();
        }

        @Bean
        ReportService reportService() {
            return new StubReportService();
        }

        @Bean
        ProductService productService() {
            return new StubProductService();
        }

        @Bean
        CustomerService customerService() {
            return new StubCustomerService();
        }

        @Bean
        GstAccountingService gstAccountingService() {
            return new StubGstAccountingService();
        }

        @Bean
        GstPdfExporter gstPdfExporter() {
            return new GstPdfExporter();
        }

        @Bean
        Gstr1ReturnService gstr1ReturnService() {
            return new StubGstr1ReturnService();
        }

        /** The real exporter renders the stub return so the export-fidelity assertion is meaningful. */
        @Bean
        Gstr1Exporter gstr1Exporter() {
            return new Gstr1Exporter(new ObjectMapper());
        }

        @Bean
        ExcelReportExporter excelReportExporter() {
            return new ExcelReportExporter();
        }

        @Bean
        PdfReportExporter pdfReportExporter() {
            return new PdfReportExporter();
        }

        @Bean
        CsvReportExporter csvReportExporter() {
            return new CsvReportExporter();
        }
    }

    /** Returns canned results for the guarded actions so a permitted call yields 2xx. */
    static class StubAdminOrderService extends AdminOrderService {
        StubAdminOrderService() {
            super(null, null, null);
        }

        @Override
        public OrderResponse approve(Long id, AuthPrincipal admin) {
            return sampleOrderResponse();
        }

        @Override
        public OrderResponse reject(Long id, String reason, AuthPrincipal admin) {
            return sampleOrderResponse();
        }
    }

    static class StubPackingService extends PackingService {
        StubPackingService() {
            super(null, null, null, null);
        }

        @Override
        public PackingScanResponse scan(String barcode, AuthPrincipal actor) {
            return null; // controller returns it directly → 200
        }

        @Override
        public OrderResponse handover(Long orderId, AuthPrincipal actor,
                                      com.shifa.oms.packing.dto.HandoverRequest request) {
            return null;
        }

        @Override
        public OrderResponse dispatch(Long orderId, AuthPrincipal actor) {
            return null;
        }
    }

    static class StubRoleDashboardService extends RoleDashboardService {
        StubRoleDashboardService() {
            super(null, null, null, null, null);
        }

        @Override
        public RoleDashboardSummary summary(AuthPrincipal principal) {
            return null;
        }
    }

    static class StubReportService extends ReportService {
        StubReportService() {
            super(null, null, null, null, null, null, null);
        }

        @Override
        public ReportResponse generate(ReportType type, LocalDate from, LocalDate to) {
            return null;
        }
    }

    /** Canned product reads/writes so a permitted call passes the guard and yields 2xx. */
    static class StubProductService extends ProductService {
        StubProductService() {
            super(null, null, null, null);
        }

        @Override
        public List<ProductResponse> adminList() {
            return List.of();
        }

        @Override
        public ProductSalesStatsResponse salesStats(Long id) {
            return ProductSalesStatsResponse.ZERO;
        }

        @Override
        public Page<ProductResponse> adminList(String q, String category,
                                               ProductVisibility visibility,
                                               StockStatus stockStatus, Pageable pageable) {
            return Page.empty(pageable);
        }

        @Override
        public ProductResponse adminDetail(Long id) {
            return null;
        }

        @Override
        public ProductResponse create(ProductRequest request) {
            return null;
        }

        @Override
        public ProductResponse update(Long id, ProductRequest request) {
            return null;
        }
    }

    /** Canned customer reads so a permitted call passes the guard and yields 2xx. */
    static class StubCustomerService extends CustomerService {
        StubCustomerService() {
            super(null, null, null, null, null);
        }

        @Override
        public PageResponse<CustomerSummaryResponse> list(String q, Pageable pageable) {
            return null;
        }

        @Override
        public CustomerDetailResponse get(String mobile) {
            return null;
        }
    }

    /** Canned GST accounting service so the guarded GstController bean wires without a database. */
    static class StubGstAccountingService extends GstAccountingService {
        StubGstAccountingService() {
            super(null, null, null, null, null);
        }
    }

    /**
     * Returns a fixed, non-trivial {@link Gstr1Return} for every period so the guard calls yield 2xx
     * and the export-fidelity test has real section figures to reconcile. The real {@link Gstr1Exporter}
     * renders this same return, so its exported totals must equal the {@code /gstr1} response totals.
     */
    static class StubGstr1ReturnService extends Gstr1ReturnService {
        StubGstr1ReturnService() {
            super(null, null, null, null);
        }

        @Override
        public Gstr1Return build(LocalDate from, LocalDate to) {
            return sampleGstr1Return();
        }
    }

    /** A deterministic GSTR-1 return with B2B, B2CS, HSN, and docs rows carrying clean 2-decimal figures. */
    private static Gstr1Return sampleGstr1Return() {
        B2bRow b2b = new B2bRow(
                "29ABCDE1234F1Z5", "SHR-B2B01", LocalDate.of(2026, 1, 10),
                new BigDecimal("1180.00"), "Karnataka", "29", new BigDecimal("18"),
                new BigDecimal("1000.00"), new BigDecimal("0.00"), new BigDecimal("0.00"),
                new BigDecimal("180.00"));
        B2csRow b2cs = new B2csRow(
                "OE", "Madhya Pradesh", "23", SupplyType.INTRA, new BigDecimal("5"),
                new BigDecimal("500.00"), new BigDecimal("12.50"), new BigDecimal("12.50"),
                new BigDecimal("0.00"));
        HsnRow hsn = new HsnRow(
                "30049011", "NOS", new BigDecimal("18"), new BigDecimal("2"),
                new BigDecimal("1500.00"), new BigDecimal("6.25"), new BigDecimal("6.25"),
                new BigDecimal("180.00"), true, null);
        DocRow doc = new DocRow("Invoices for outward supply", "INV-1", "INV-9", 9, 1);
        Gstr3bSummary reconciliation = new Gstr3bSummary(
                new BigDecimal("1500.00"), new BigDecimal("12.50"), new BigDecimal("12.50"),
                new BigDecimal("180.00"), new BigDecimal("205.00"), new BigDecimal("1680.00"));
        return new Gstr1Return(
                "23AABCS1234F1Z5", 1, 2026,
                List.of(b2b), List.of(), List.of(b2cs), List.of(), List.of(),
                List.of(hsn), List.of(doc), List.of(), reconciliation);
    }
}
