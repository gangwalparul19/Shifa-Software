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
import com.shifa.oms.audit.AuditEventRepository;
import com.shifa.oms.crm.CustomerController;
import com.shifa.oms.crm.CustomerService;
import com.shifa.oms.crm.dto.CustomerDetailResponse;
import com.shifa.oms.crm.dto.CustomerSummaryResponse;
import com.shifa.oms.ledger.AccountGroup;
import com.shifa.oms.ledger.AccountGroupRepository;
import com.shifa.oms.ledger.ChartOfAccountsService;
import com.shifa.oms.ledger.DayBookService;
import com.shifa.oms.ledger.FinancialYear;
import com.shifa.oms.ledger.FinancialYearRepository;
import com.shifa.oms.ledger.FinancialYearService;
import com.shifa.oms.ledger.FinancialYearService.Period;
import com.shifa.oms.ledger.LedgerAccount;
import com.shifa.oms.ledger.LedgerAccountRepository;
import com.shifa.oms.ledger.LedgerController;
import com.shifa.oms.ledger.LedgerViewService;
import com.shifa.oms.ledger.OpeningBalance;
import com.shifa.oms.ledger.OpeningBalanceRepository;
import com.shifa.oms.ledger.OpeningBalanceService;
import com.shifa.oms.ledger.ReportPeriodResolver;
import com.shifa.oms.ledger.TrialBalanceService;
import com.shifa.oms.ledger.Voucher;
import com.shifa.oms.ledger.VoucherLineRepository;
import com.shifa.oms.ledger.VoucherRepository;
import com.shifa.oms.ledger.VoucherService;
import com.shifa.oms.ledger.domain.AccountNature;
import com.shifa.oms.ledger.domain.DrCr;
import com.shifa.oms.ledger.domain.VoucherType;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
        GstController.class,
        LedgerController.class},
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

    // --- Accounting / General Ledger: reads ADMIN+ACCOUNTANT+CA, writes ADMIN+ACCOUNTANT (Req 16) ----

    /**
     * The full role set the {@code /api/accounting/**} endpoints are guarded against (Req 16): the
     * three finance roles the class-level {@code @PreAuthorize hasAnyRole('ADMIN','ACCOUNTANT','CA')}
     * admits for read access (Reqs 16.1, 16.3, 18.1), plus the four non-finance roles that must be
     * rejected with 403 on any accounting endpoint (Req 16.2). The mutating endpoints additionally
     * exclude CA via a method-level {@code hasAnyRole('ADMIN','ACCOUNTANT')} (Reqs 16.4, 16.5).
     */
    private static final List<Role> ACCOUNTING_ROLES = List.of(
            Role.ADMIN, Role.ACCOUNTANT, Role.CA,
            Role.SALESPERSON, Role.PACKING_USER, Role.TEAM_LEAD, Role.PAYMENT_VERIFIER);

    /** The three finance roles allowed to READ every accounting view (Reqs 16.1, 16.3, 18.1). */
    private static final List<Role> ACCOUNTING_READ_ROLES = List.of(Role.ADMIN, Role.ACCOUNTANT, Role.CA);

    /**
     * The two roles allowed to POST/reverse vouchers and edit the Chart of Accounts (Reqs 16.4, 16.5);
     * CA is deliberately excluded so it is read-only.
     */
    private static final List<Role> ACCOUNTING_WRITE_ROLES = List.of(Role.ADMIN, Role.ACCOUNTANT);

    // Read views: ADMIN + ACCOUNTANT + CA succeed; the four non-finance roles get 403 (Reqs 16.1–16.3, 18.1).

    @Test
    void accountGroupsListReadIsFinanceRoles() throws Exception {
        assertAccountingRoleMatrix(get("/api/accounting/account-groups"), ACCOUNTING_READ_ROLES);
    }

    @Test
    void ledgersListReadIsFinanceRoles() throws Exception {
        assertAccountingRoleMatrix(get("/api/accounting/ledgers"), ACCOUNTING_READ_ROLES);
    }

    @Test
    void financialYearsListReadIsFinanceRoles() throws Exception {
        assertAccountingRoleMatrix(get("/api/accounting/financial-years"), ACCOUNTING_READ_ROLES);
    }

    @Test
    void openingBalancesReadIsFinanceRoles() throws Exception {
        assertAccountingRoleMatrix(get("/api/accounting/opening-balances"), ACCOUNTING_READ_ROLES);
    }

    @Test
    void dayBookReadIsFinanceRoles() throws Exception {
        assertAccountingRoleMatrix(get("/api/accounting/vouchers"), ACCOUNTING_READ_ROLES);
    }

    @Test
    void voucherDetailReadIsFinanceRoles() throws Exception {
        assertAccountingRoleMatrix(get("/api/accounting/vouchers/5"), ACCOUNTING_READ_ROLES);
    }

    @Test
    void voucherAuditReadIsFinanceRoles() throws Exception {
        assertAccountingRoleMatrix(get("/api/accounting/vouchers/5/audit"), ACCOUNTING_READ_ROLES);
    }

    @Test
    void ledgerStatementReadIsFinanceRoles() throws Exception {
        assertAccountingRoleMatrix(get("/api/accounting/ledgers/5/statement"), ACCOUNTING_READ_ROLES);
    }

    @Test
    void trialBalanceReadIsFinanceRoles() throws Exception {
        assertAccountingRoleMatrix(get("/api/accounting/trial-balance"), ACCOUNTING_READ_ROLES);
    }

    // Mutations: only ADMIN + ACCOUNTANT succeed; CA AND the four non-finance roles get 403 (Reqs 16.4, 16.5).

    /** Valid account-group create body so the ONLY decision under test on the write is the guard. */
    private static final String VALID_ACCOUNT_GROUP_JSON = """
            {"name":"Guard Group","nature":"ASSET"}
            """;

    /** Valid ledger create body. */
    private static final String VALID_LEDGER_JSON = """
            {"name":"Guard Ledger","accountGroupId":1}
            """;

    /** Valid opening-balance upsert body. */
    private static final String VALID_OPENING_BALANCE_JSON = """
            {"ledgerAccountId":1,"financialYearId":1,"amount":"100.00","side":"DEBIT"}
            """;

    /** Valid, balanced two-line journal voucher body. */
    private static final String VALID_VOUCHER_JSON = """
            {"type":"JOURNAL","date":"2026-01-10","narration":"Guard voucher","lines":[
              {"ledgerAccountId":1,"side":"DEBIT","amount":"100.00"},
              {"ledgerAccountId":2,"side":"CREDIT","amount":"100.00"}]}
            """;

    @Test
    void createAccountGroupIsAdminOrAccountant() throws Exception {
        assertAccountingRoleMatrix(
                post("/api/accounting/account-groups")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_ACCOUNT_GROUP_JSON),
                ACCOUNTING_WRITE_ROLES);
    }

    @Test
    void createLedgerIsAdminOrAccountant() throws Exception {
        assertAccountingRoleMatrix(
                post("/api/accounting/ledgers")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_LEDGER_JSON),
                ACCOUNTING_WRITE_ROLES);
    }

    @Test
    void deleteLedgerIsAdminOrAccountant() throws Exception {
        assertAccountingRoleMatrix(delete("/api/accounting/ledgers/5"), ACCOUNTING_WRITE_ROLES);
    }

    @Test
    void closeFinancialYearIsAdminOrAccountant() throws Exception {
        assertAccountingRoleMatrix(post("/api/accounting/financial-years/5/close"), ACCOUNTING_WRITE_ROLES);
    }

    @Test
    void recordOpeningBalanceIsAdminOrAccountant() throws Exception {
        assertAccountingRoleMatrix(
                post("/api/accounting/opening-balances")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_OPENING_BALANCE_JSON),
                ACCOUNTING_WRITE_ROLES);
    }

    @Test
    void postVoucherIsAdminOrAccountant() throws Exception {
        assertAccountingRoleMatrix(
                post("/api/accounting/vouchers")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_VOUCHER_JSON),
                ACCOUNTING_WRITE_ROLES);
    }

    @Test
    void reverseVoucherIsAdminOrAccountant() throws Exception {
        assertAccountingRoleMatrix(post("/api/accounting/vouchers/5/reverse"), ACCOUNTING_WRITE_ROLES);
    }

    /**
     * As {@link #assertRoleMatrix} but over the accounting role set {@link #ACCOUNTING_ROLES} — so the
     * {@code /api/accounting/**} endpoints are asserted allowed for {@code allowed} and forbidden (403)
     * for every other role, covering the three finance roles AND the four non-finance roles (Req 16).
     */
    private void assertAccountingRoleMatrix(MockHttpServletRequestBuilder request, List<Role> allowed)
            throws Exception {
        for (Role role : ACCOUNTING_ROLES) {
            if (allowed.contains(role)) {
                mvc.perform(request.with(authFor(role)))
                        .andExpect(status().is2xxSuccessful());
            } else {
                mvc.perform(request.with(authFor(role)))
                        .andExpect(status().isForbidden());
            }
        }
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

        // --- General Ledger (accounting) collaborators for LedgerController -------------

        @Bean
        ChartOfAccountsService chartOfAccountsService() {
            return new StubChartOfAccountsService();
        }

        @Bean
        FinancialYearService financialYearService() {
            return new StubFinancialYearService();
        }

        @Bean
        OpeningBalanceService openingBalanceService() {
            return new StubOpeningBalanceService();
        }

        @Bean
        VoucherService voucherService() {
            return new StubVoucherService();
        }

        @Bean
        DayBookService dayBookService() {
            return new StubDayBookService();
        }

        @Bean
        LedgerViewService ledgerViewService() {
            return new StubLedgerViewService();
        }

        @Bean
        TrialBalanceService trialBalanceService() {
            return new StubTrialBalanceService();
        }

        @Bean
        ReportPeriodResolver reportPeriodResolver() {
            return new StubReportPeriodResolver();
        }

        /** Mocked repositories: default answers give empty lists/Optionals; the voucher lookup is
         * stubbed so the voucher-detail read returns 2xx for a permitted caller (denied callers are
         * rejected by the guard before the body runs, so the stub value is irrelevant to them). */
        @Bean
        AccountGroupRepository ledgerAccountGroupRepository() {
            return mock(AccountGroupRepository.class);
        }

        @Bean
        LedgerAccountRepository ledgerAccountRepository() {
            return mock(LedgerAccountRepository.class);
        }

        @Bean
        FinancialYearRepository financialYearRepository() {
            return mock(FinancialYearRepository.class);
        }

        @Bean
        OpeningBalanceRepository openingBalanceRepository() {
            return mock(OpeningBalanceRepository.class);
        }

        @Bean
        VoucherRepository voucherRepository() {
            VoucherRepository repository = mock(VoucherRepository.class);
            when(repository.findById(anyLong())).thenReturn(Optional.of(sampleVoucher()));
            return repository;
        }

        @Bean
        VoucherLineRepository voucherLineRepository() {
            return mock(VoucherLineRepository.class);
        }

        @Bean
        AuditEventRepository auditEventRepository() {
            return mock(AuditEventRepository.class);
        }
    }

    // --- Ledger stub services: canned results so a permitted call passes the guard and yields 2xx ---

    static class StubChartOfAccountsService extends ChartOfAccountsService {
        StubChartOfAccountsService() {
            super(null, null, null);
        }

        @Override
        public AccountGroup createGroup(String name, AccountNature nature, Long parentGroupId) {
            return new AccountGroup("Guard Group", AccountNature.ASSET, null, false);
        }

        @Override
        public LedgerAccount createLedger(String name, Long groupId) {
            return new LedgerAccount("Guard Ledger", 1L, null, false);
        }

        @Override
        public void deleteLedger(Long id) {
            // no-op: a permitted delete returns 204
        }
    }

    static class StubFinancialYearService extends FinancialYearService {
        StubFinancialYearService() {
            super(null, null, null, null, null, null, null);
        }

        @Override
        public FinancialYear close(Long financialYearId) {
            return sampleFinancialYear();
        }
    }

    static class StubOpeningBalanceService extends OpeningBalanceService {
        StubOpeningBalanceService() {
            super(null, null, null);
        }

        @Override
        public OpeningBalance recordOpeningBalance(Long ledgerAccountId, Long financialYearId,
                                                   BigDecimal amount, DrCr side) {
            return new OpeningBalance(1L, 1L, new BigDecimal("100.00"), DrCr.DEBIT);
        }

        @Override
        public OpeningBalanceCheck checkBalance(Long financialYearId) {
            return new OpeningBalanceCheck(true, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    static class StubVoucherService extends VoucherService {
        StubVoucherService() {
            super(null, null, null, null, null, null, null, null);
        }

        @Override
        public Voucher post(PostVoucherCommand command) {
            return sampleVoucher();
        }

        @Override
        public Voucher reverse(Long voucherId) {
            return sampleVoucher();
        }
    }

    static class StubDayBookService extends DayBookService {
        StubDayBookService() {
            super(null, null, null);
        }

        @Override
        public DayBook dayBook(Long financialYearId, LocalDate from, LocalDate to, VoucherType voucherType) {
            return new DayBook(LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), 1L, voucherType, List.of());
        }
    }

    static class StubLedgerViewService extends LedgerViewService {
        StubLedgerViewService() {
            super(null, null, null, null, null, null, null);
        }

        @Override
        public LedgerStatement statement(Long ledgerAccountId, Long financialYearId,
                                         LocalDate from, LocalDate to) {
            Period period = new Period(LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), 1L);
            return new LedgerStatement(1L, "Cash", AccountNature.ASSET, period, null, List.of(), null);
        }
    }

    static class StubTrialBalanceService extends TrialBalanceService {
        StubTrialBalanceService() {
            super(null, null, null, null, null, null);
        }

        @Override
        public TrialBalanceReport trialBalance(Long financialYearId, LocalDate from, LocalDate to) {
            Period period = new Period(LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), 1L);
            return new TrialBalanceReport(period, List.of(),
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, true);
        }
    }

    static class StubReportPeriodResolver extends ReportPeriodResolver {
        StubReportPeriodResolver() {
            super((FinancialYearService) null);
        }

        @Override
        public Period resolve(Long financialYearId, LocalDate from, LocalDate to) {
            return new Period(LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), 1L);
        }
    }

    /** A posted JOURNAL voucher (no id needed) so voucher reads/writes render to 2xx. */
    private static Voucher sampleVoucher() {
        return new Voucher(VoucherType.JOURNAL, LocalDate.of(2026, 1, 10), 1L, "JV/2026/1",
                "Guard voucher", "admin", null, null, null);
    }

    /** A current, open financial year for the close-year write. */
    private static FinancialYear sampleFinancialYear() {
        return new FinancialYear(LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), "2025-26");
    }

    /** Returns canned results for the guarded actions so a permitted call yields 2xx. */
    static class StubAdminOrderService extends AdminOrderService {
        StubAdminOrderService() {
            super(null, null, null, null);
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
