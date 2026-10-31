package com.shifa.oms.reporting;

import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.reporting.domain.DateRange;
import com.shifa.oms.reporting.domain.OrderReportRecord;
import com.shifa.oms.reporting.domain.OrderReportRecord.ProductLine;
import com.shifa.oms.reporting.domain.ReportTableBuilder;
import com.shifa.oms.reporting.domain.ReportType;
import com.shifa.oms.reporting.domain.TabularData;
import com.shifa.oms.statemachine.OrderStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the sales-executive &times; product reports (client request):
 * the detail report, the executive-tracker grid, and the top-3 executives per
 * category / product. Pure — no Spring/DB — over hand-built records, with every
 * expected figure worked out by hand from the fixture below.
 *
 * <p>Fixture (window = May 2026). Categories: Joint Red + Joint Plus = "Joint Care",
 * Gut Cleanser = "Digestive", Booster = none (Uncategorised).
 * <pre>
 *  O1  sp7  A  DELIVERED  Joint Red x2 @100, Gut Cleanser x1 @300   total 480 (20 discount)
 *  O2  sp7  A  DELIVERED  Joint Red x1 @100                          total 100  (A repeats)
 *  O3  sp7  B  APPROVED   Joint Plus x1 @400                         total 400
 *  O4  sp9  C  DELIVERED  Joint Red x4 @100                          total 400
 *  O5  sp9  D  DELIVERED  Joint Plus x1 @400                         total 400
 *  O6  sp11 E  DELIVERED  Joint Red x1 @100                          total 100
 *  O7  sp12 F  DELIVERED  Joint Red x1 @100                          total 100
 *  O8  none G  DELIVERED  Joint Red x100 @100 (Shopify)              total 9000
 *  O9  sp7  CANCELLED     Joint Red x50  (not a sale)
 *  O10 sp7  June          Joint Red x50  (outside the window)
 *  O11 sp9  C  DELIVERED  Booster x10 @50                            total 500
 * </pre>
 */
class ExecutiveProductReportsTest {

    private static final DateRange MAY = new DateRange(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31));
    private static final DateRange ALL = DateRange.all();

    private final ReportTableBuilder builder = new ReportTableBuilder();

    private static ProductLine line(String name, String category, int qty, String rate) {
        BigDecimal r = new BigDecimal(rate);
        return new ProductLine(name, qty, r, r.multiply(BigDecimal.valueOf(qty)), category);
    }

    private static OrderReportRecord order(long id, Long salespersonId, LocalDate date, OrderStatus status,
                                           String mobile, String name, String total, ProductLine... lines) {
        return new OrderReportRecord(
                id, "SHR-" + id, date, salespersonId, name, mobile, "MH", List.of(lines),
                new BigDecimal(total), BigDecimal.ZERO, BigDecimal.ZERO,
                PaymentStatus.FULLY_PAID, status, "N/A", "N/A", "AWB", null, null);
    }

    private static List<OrderReportRecord> fixture() {
        LocalDate may1 = LocalDate.of(2026, 5, 1);
        List<OrderReportRecord> orders = new ArrayList<>();
        orders.add(order(1, 7L, may1, OrderStatus.DELIVERED, "A", "Asha", "480",
                line("Joint Red", "Joint Care", 2, "100"), line("Gut Cleanser", "Digestive", 1, "300")));
        orders.add(order(2, 7L, LocalDate.of(2026, 5, 10), OrderStatus.DELIVERED, "A", "Asha", "100",
                line("Joint Red", "Joint Care", 1, "100")));
        orders.add(order(3, 7L, LocalDate.of(2026, 5, 11), OrderStatus.APPROVED, "B", "Bina", "400",
                line("Joint Plus", "Joint Care", 1, "400")));
        orders.add(order(4, 9L, LocalDate.of(2026, 5, 2), OrderStatus.DELIVERED, "C", "Chitra", "400",
                line("Joint Red", "Joint Care", 4, "100")));
        orders.add(order(5, 9L, LocalDate.of(2026, 5, 3), OrderStatus.DELIVERED, "D", "Deepa", "400",
                line("Joint Plus", "Joint Care", 1, "400")));
        orders.add(order(6, 11L, LocalDate.of(2026, 5, 4), OrderStatus.DELIVERED, "E", "Esha", "100",
                line("Joint Red", "Joint Care", 1, "100")));
        orders.add(order(7, 12L, LocalDate.of(2026, 5, 5), OrderStatus.DELIVERED, "F", "Farah", "100",
                line("Joint Red", "Joint Care", 1, "100")));
        orders.add(order(8, null, LocalDate.of(2026, 5, 6), OrderStatus.DELIVERED, "G", "Gita", "9000",
                line("Joint Red", "Joint Care", 100, "100")));
        orders.add(order(9, 7L, LocalDate.of(2026, 5, 7), OrderStatus.CANCELLED, "A", "Asha", "5000",
                line("Joint Red", "Joint Care", 50, "100")));
        orders.add(order(10, 7L, LocalDate.of(2026, 6, 2), OrderStatus.DELIVERED, "A", "Asha", "5000",
                line("Joint Red", "Joint Care", 50, "100")));
        orders.add(order(11, 9L, LocalDate.of(2026, 5, 8), OrderStatus.DELIVERED, "C", "Chitra", "500",
                line("Booster", null, 10, "50")));
        return orders;
    }

    // --- Detail --------------------------------------------------------------

    @Test
    void detailGroupsByExecutiveAndProductWithCategoryAveragesAndShare() {
        TabularData t = builder.build(ReportType.EXECUTIVE_PRODUCT_DETAIL, fixture(), MAY);

        assertThat(t.headers()).containsExactly(
                "Sales Executive", "Category", "Product", "Orders", "Customers", "Quantity",
                "Total Sales", "Avg Price", "Share of Executive Sales %");

        List<List<String>> rows = t.rows();
        // sp7: Joint Plus 400, then the 300/300 tie by product name (Gut Cleanser, Joint Red).
        assertThat(rows.get(0)).containsExactly("7", "Joint Care", "Joint Plus", "1", "1", "1", "400.00", "400.00", "40.00");
        assertThat(rows.get(1)).containsExactly("7", "Digestive", "Gut Cleanser", "1", "1", "1", "300.00", "300.00", "30.00");
        // Joint Red: O1 + O2 (the cancelled O9 and June O10 are excluded) -> 2 orders, 1 customer (A), qty 3.
        assertThat(rows.get(2)).containsExactly("7", "Joint Care", "Joint Red", "2", "1", "3", "300.00", "100.00", "30.00");
    }

    @Test
    void detailKeepsUnattributedOrdersUnderUnspecifiedAndSortsThemLast() {
        List<List<String>> rows = builder.build(ReportType.EXECUTIVE_PRODUCT_DETAIL, fixture(), MAY).rows();

        List<String> last = rows.get(rows.size() - 1);
        assertThat(last).containsExactly("UNSPECIFIED", "Joint Care", "Joint Red", "1", "1", "100", "10000.00", "100.00", "100.00");
    }

    @Test
    void detailReportsUncategorisedProductsUnderTheFallbackLabel() {
        List<List<String>> rows = builder.build(ReportType.EXECUTIVE_PRODUCT_DETAIL, fixture(), MAY).rows();

        assertThat(rows).anySatisfy(r -> assertThat(r).startsWith("9", "Uncategorised", "Booster"));
    }

    // --- Grid ----------------------------------------------------------------

    @Test
    void gridHasOneColumnPerProductAlphabeticallyAndRanksByGrossSale() {
        TabularData t = builder.build(ReportType.EXECUTIVE_PRODUCT_MATRIX, fixture(), MAY);

        assertThat(t.headers()).containsExactly(
                "Sales Executive", "Orders", "Gross Sale", "Net Sale", "Total Qty", "Distinct Products",
                "Booster", "Gut Cleanser", "Joint Plus", "Joint Red");

        List<List<String>> rows = t.rows();
        assertThat(rows.get(0)).containsExactly("UNSPECIFIED", "1", "10000.00", "9000.00", "100", "1", "0", "0", "0", "100");
        assertThat(rows.get(1)).containsExactly("9", "3", "1300.00", "1300.00", "15", "3", "10", "0", "1", "4");
        // Executive 7: gross 1000 (line totals) vs net 980 (O1 carried a 20 discount); cancelled/June excluded.
        assertThat(rows.get(2)).containsExactly("7", "3", "1000.00", "980.00", "5", "3", "0", "1", "1", "3");
        // 100/100 tie broken by executive id.
        assertThat(rows.get(3)).containsExactly("11", "1", "100.00", "100.00", "1", "1", "0", "0", "0", "1");
        assertThat(rows.get(4)).containsExactly("12", "1", "100.00", "100.00", "1", "1", "0", "0", "0", "1");
    }

    @Test
    void gridEndsWithATotalRowThatReconcilesWithTheExecutiveRows() {
        List<List<String>> rows = builder.build(ReportType.EXECUTIVE_PRODUCT_MATRIX, fixture(), MAY).rows();

        assertThat(rows).hasSize(6);
        assertThat(rows.get(5)).containsExactly(
                ReportTableBuilder.TOTAL_ROW_LABEL, "9", "12500.00", "11480.00", "122", "4", "10", "1", "2", "109");
    }

    @Test
    void gridWithNoSalesHasOnlyTheFixedHeadersAndNoTotalRow() {
        TabularData t = builder.build(ReportType.EXECUTIVE_PRODUCT_MATRIX, List.of(), ALL);

        assertThat(t.headers()).containsExactly(
                "Sales Executive", "Orders", "Gross Sale", "Net Sale", "Total Qty", "Distinct Products");
        assertThat(t.rows()).isEmpty();
    }

    // --- Top executives by category -----------------------------------------

    @Test
    void topByCategoryReturnsAtMostThreeRankedExecutivesPerCategory() {
        TabularData t = builder.build(ReportType.TOP_EXECUTIVES_BY_CATEGORY, fixture(), MAY);

        assertThat(t.headers()).containsExactly(
                "Category", "Rank", "Sales Executive", "Orders", "Customers", "Quantity",
                "Total Sales", "Share of Category %", "Repeat Customers", "Repeat Rate %");

        List<List<String>> rows = t.rows();
        // Joint Care has four executives (9, 7, 11, 12); only the top three are listed.
        // Unattributed O8 (10000) is excluded, so executives are not crowded out by the Shopify order.
        assertThat(rows).hasSize(5);
        assertThat(rows.get(0)).containsExactly("Joint Care", "1", "9", "2", "2", "5", "800.00", "47.06", "0", "0.00");
        // Executive 7: customer A bought in two orders (O1, O2) -> 1 repeat of 2 customers = 50%.
        assertThat(rows.get(1)).containsExactly("Joint Care", "2", "7", "3", "2", "4", "700.00", "41.18", "1", "50.00");
        // 11 and 12 tie on sales and quantity; the lower executive id wins rank 3.
        assertThat(rows.get(2)).containsExactly("Joint Care", "3", "11", "1", "1", "1", "100.00", "5.88", "0", "0.00");
    }

    @Test
    void topByCategoryListsUncategorisedLastEvenWhenItOutsellsOtherCategories() {
        List<List<String>> rows = builder.build(ReportType.TOP_EXECUTIVES_BY_CATEGORY, fixture(), MAY).rows();

        // Uncategorised (500) outsells Digestive (300) but is still listed after it.
        assertThat(rows.get(3)).containsExactly("Digestive", "1", "7", "1", "1", "1", "300.00", "100.00", "0", "0.00");
        assertThat(rows.get(4)).containsExactly("Uncategorised", "1", "9", "1", "1", "10", "500.00", "100.00", "0", "0.00");
    }

    // --- Top executives by product ------------------------------------------

    @Test
    void topByProductRanksBestSellingProductsFirstAndBreaksTiesById() {
        TabularData t = builder.build(ReportType.TOP_EXECUTIVES_BY_PRODUCT, fixture(), MAY);

        assertThat(t.headers().get(0)).isEqualTo("Product");
        assertThat(t.headers().get(7)).isEqualTo("Share of Product %");

        List<List<String>> rows = t.rows();
        // Joint Red (900 sold by executives) -> 9 (400), 7 (300), 11 (100); 12 dropped; O8 excluded.
        assertThat(rows.get(0)).containsExactly("Joint Red", "1", "9", "1", "1", "4", "400.00", "44.44", "0", "0.00");
        assertThat(rows.get(1)).containsExactly("Joint Red", "2", "7", "2", "1", "3", "300.00", "33.33", "1", "100.00");
        assertThat(rows.get(2)).containsExactly("Joint Red", "3", "11", "1", "1", "1", "100.00", "11.11", "0", "0.00");
        // Joint Plus (800): 7 and 9 tie on sales and quantity -> executive 7 ranks first.
        assertThat(rows.get(3)).containsExactly("Joint Plus", "1", "7", "1", "1", "1", "400.00", "50.00", "0", "0.00");
        assertThat(rows.get(4)).containsExactly("Joint Plus", "2", "9", "1", "1", "1", "400.00", "50.00", "0", "0.00");
        assertThat(rows.get(5).get(0)).isEqualTo("Booster");
        assertThat(rows.get(6).get(0)).isEqualTo("Gut Cleanser");
        assertThat(rows).hasSize(7);
    }

    // --- Rules shared by every report ---------------------------------------

    @Test
    void nonRevenueOrdersAndOrdersOutsideTheWindowNeverCount() {
        // Without the window the June order (qty 50) joins executive 7's Joint Red; the cancelled O9 still never does.
        List<List<String>> rows = builder.build(ReportType.EXECUTIVE_PRODUCT_DETAIL, fixture(), ALL).rows();

        assertThat(rows).anySatisfy(r -> assertThat(r).startsWith("7", "Joint Care", "Joint Red", "3", "1", "53"));
    }

    @Test
    void anonymousOrdersAreNeverMergedIntoAFalseRepeatCustomer() {
        List<OrderReportRecord> orders = List.of(
                order(1, 5L, LocalDate.of(2026, 5, 1), OrderStatus.DELIVERED, null, null, "100",
                        line("Joint Red", "Joint Care", 1, "100")),
                order(2, 5L, LocalDate.of(2026, 5, 2), OrderStatus.DELIVERED, " ", " ", "100",
                        line("Joint Red", "Joint Care", 1, "100")));

        List<String> row = builder.build(ReportType.TOP_EXECUTIVES_BY_CATEGORY, orders, MAY).rows().get(0);

        assertThat(row).containsExactly("Joint Care", "1", "5", "2", "2", "2", "200.00", "100.00", "0", "0.00");
    }

    @Test
    void customersWithoutAMobileAreIdentifiedByNameIgnoringCase() {
        List<OrderReportRecord> orders = List.of(
                order(1, 5L, LocalDate.of(2026, 5, 1), OrderStatus.DELIVERED, null, "Asha", "100",
                        line("Joint Red", "Joint Care", 1, "100")),
                order(2, 5L, LocalDate.of(2026, 5, 2), OrderStatus.DELIVERED, null, " asha ", "100",
                        line("Joint Red", "Joint Care", 1, "100")));

        List<String> row = builder.build(ReportType.TOP_EXECUTIVES_BY_CATEGORY, orders, MAY).rows().get(0);

        // One customer who ordered twice -> 1 repeat customer, 100% repeat rate.
        assertThat(row).containsExactly("Joint Care", "1", "5", "2", "1", "2", "200.00", "100.00", "1", "100.00");
    }

    @Test
    void everyNewReportToleratesAnEmptyOrderList() {
        for (ReportType type : List.of(ReportType.EXECUTIVE_PRODUCT_DETAIL, ReportType.EXECUTIVE_PRODUCT_MATRIX,
                ReportType.TOP_EXECUTIVES_BY_CATEGORY, ReportType.TOP_EXECUTIVES_BY_PRODUCT)) {
            TabularData t = builder.build(type, List.of(), ALL);
            assertThat(t.headers()).as(type.name()).isNotEmpty();
            assertThat(t.rows()).as(type.name()).isEmpty();
        }
    }

    @Test
    void everyRowHasOneCellPerHeader() {
        for (ReportType type : List.of(ReportType.EXECUTIVE_PRODUCT_DETAIL, ReportType.EXECUTIVE_PRODUCT_MATRIX,
                ReportType.TOP_EXECUTIVES_BY_CATEGORY, ReportType.TOP_EXECUTIVES_BY_PRODUCT)) {
            TabularData t = builder.build(type, fixture(), MAY);
            assertThat(t.rows()).as(type.name()).isNotEmpty();
            for (List<String> row : t.rows()) {
                assertThat(row).as(type.name()).hasSameSizeAs(t.headers());
            }
        }
    }

    // --- Wiring --------------------------------------------------------------

    @Test
    void salespersonColumnPointsAtTheIdColumnOfEachReport() {
        assertThat(ReportTableBuilder.salespersonColumn(ReportType.EXECUTIVE_PRODUCT_DETAIL)).isZero();
        assertThat(ReportTableBuilder.salespersonColumn(ReportType.EXECUTIVE_PRODUCT_MATRIX)).isZero();
        assertThat(ReportTableBuilder.salespersonColumn(ReportType.TOP_EXECUTIVES_BY_CATEGORY)).isEqualTo(2);
        assertThat(ReportTableBuilder.salespersonColumn(ReportType.TOP_EXECUTIVES_BY_PRODUCT)).isEqualTo(2);
        assertThat(ReportTableBuilder.salespersonColumn(ReportType.SALESPERSON_PRODUCT)).isZero();
        assertThat(ReportTableBuilder.salespersonColumn(ReportType.PRODUCT)).isEqualTo(-1);
    }

    @Test
    void reportTypeParsesTheNewUrlSegments() {
        assertThat(ReportType.from("executive-product-detail")).isEqualTo(ReportType.EXECUTIVE_PRODUCT_DETAIL);
        assertThat(ReportType.from("executive-product-matrix")).isEqualTo(ReportType.EXECUTIVE_PRODUCT_MATRIX);
        assertThat(ReportType.from("top-executives-by-category")).isEqualTo(ReportType.TOP_EXECUTIVES_BY_CATEGORY);
        assertThat(ReportType.from("top-executives-by-product")).isEqualTo(ReportType.TOP_EXECUTIVES_BY_PRODUCT);
    }
}
