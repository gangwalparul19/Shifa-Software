package com.shifa.oms.reconciliation;

import com.shifa.oms.audit.AuditService;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.courier.CourierRecordRepository;
import com.shifa.oms.order.OrderPaymentRepository;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.quikshipx.OrderShipmentRepository;
import com.shifa.oms.reconciliation.dto.SettlementImportResponse;
import com.shifa.oms.reconciliation.dto.SettlementLineRequest;
import com.shifa.oms.reconciliation.dto.SettlementLineResponse;
import com.shifa.oms.settings.BankAccount;
import com.shifa.oms.settings.BankAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SettlementServiceTest {

    private SettlementService service;
    private OrderPaymentRepository paymentRepository;
    private BankAccountRepository bankAccountRepository;

    @BeforeEach
    void setUp() {
        paymentRepository = mock(OrderPaymentRepository.class);
        bankAccountRepository = mock(BankAccountRepository.class);
        service = new SettlementService(
                mock(SettlementBatchRepository.class),
                mock(SettlementLineRepository.class),
                mock(SettlementAllocationRepository.class),
                mock(OrderRepository.class),
                paymentRepository,
                mock(OrderShipmentRepository.class),
                mock(CourierRecordRepository.class),
                mock(ReceivableRepository.class),
                bankAccountRepository,
                new AuditService(null, null));
    }

    @Test
    void bankReceiptMatchingPassesCanonicalUtrToPaymentRepository() {
        BankAccount destination = new BankAccount("Primary UPI", com.shifa.oms.settings.BankAccountType.UPI_QR);
        ReflectionTestUtils.setField(destination, "id", 22L);
        when(bankAccountRepository.findById(22L)).thenReturn(java.util.Optional.of(destination));
        when(paymentRepository.findByUtr("AB12", 22L)).thenReturn(java.util.List.of());

        SettlementImportResponse response = service.importFile(
                ("UTR,Amount\n" + " ab 12" + "\t" + ",100.00\n").getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "bank.csv", SettlementSource.BANK_RECEIPT, 22L, null,
                java.time.LocalDate.of(2026, 10, 30), true, null);

        assertThat(response.lines()).hasSize(1);
        assertThat(response.lines().get(0).matchStatus()).isEqualTo(SettlementMatchStatus.UNMATCHED);
        org.mockito.Mockito.verify(paymentRepository).findByUtr("AB12", 22L);
    }

    @Test
    void dryRunMarksMissingDateAsDefaultedAndMalformedDateAsInvalid() {
        byte[] csv = ("Amount,Date\n"
                + "10.00,\n"
                + "11.00,not-a-date\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);

        SettlementImportResponse response = service.importFile(csv, "statement.csv", SettlementSource.SHOPIFY_PAYOUT,
                null, "ref-1", java.time.LocalDate.of(2026, 10, 30), true, null);

        assertThat(response.lines()).hasSize(2);
        SettlementLineResponse missingDate = response.lines().get(0);
        assertThat(missingDate.dateDefaulted()).isTrue();
        assertThat(missingDate.transactionDate()).isEqualTo(java.time.LocalDate.of(2026, 10, 30));
        assertThat(missingDate.message()).contains("defaulted to statement date");
        SettlementLineResponse malformedDate = response.lines().get(1);
        assertThat(malformedDate.matchStatus()).isEqualTo(SettlementMatchStatus.INVALID);
        assertThat(malformedDate.dateDefaulted()).isFalse();
        assertThat(malformedDate.message()).contains("malformed");
    }

    @Test
    void dryRunClassifiesZeroNegativeAndOverPrecisionAmountsAsInvalid() {
        byte[] csv = ("Amount\n0\n-1.00\n1.001\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);

        SettlementImportResponse response = service.importFile(csv, "statement.csv", SettlementSource.SHOPIFY_PAYOUT,
                null, null, java.time.LocalDate.of(2026, 10, 30), true, null);

        assertThat(response.lines()).allSatisfy(line -> {
            assertThat(line.matchStatus()).isEqualTo(SettlementMatchStatus.INVALID);
            assertThat(line.message()).contains("Amount is not valid");
        });
        assertThat(response.allocatedAmount()).isZero();
    }

    @Test
    void manualResolutionRequiresNonBlankBoundedReasonBeforeLoadingTargets() {
        SettlementLineRequest blank = new SettlementLineRequest(SettlementTargetType.ORDER_PAYMENT,
                null, 10L, null, new BigDecimal("1.00"), "  ");
        assertThatThrownBy(() -> service.resolve(1L, blank, null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("reason is required");

        SettlementLineRequest tooLong = new SettlementLineRequest(SettlementTargetType.ORDER_PAYMENT,
                null, 10L, null, new BigDecimal("1.00"), "x".repeat(501));
        assertThatThrownBy(() -> service.resolve(1L, tooLong, null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("500 characters");
    }

    @Test
    void canonicalKeysUseOnlyTheAllowedTargetId() {
        assertThat(SettlementAllocation.canonicalKey(SettlementTargetType.ORDER_PAYMENT, 7L, 8L, 9L))
                .isEqualTo("ORDER_PAYMENT:8");
        assertThat(SettlementAllocation.canonicalKey(SettlementTargetType.SHOPIFY_ORDER, 7L, 8L, 9L))
                .isEqualTo("SHOPIFY_ORDER:7");
        assertThat(SettlementAllocation.canonicalKey(SettlementTargetType.COD_RECEIVABLE, 7L, 8L, 9L))
                .isEqualTo("COD_RECEIVABLE:9");
    }
}
