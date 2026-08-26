package com.shifa.oms.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * An Indian financial year (1 April – 31 March), mapped to the {@code financial_years} table (V54).
 *
 * <p>Every voucher and opening balance is recorded against a financial year (Req 4.1, 4.2). A
 * {@link #closed} year locks posting: {@code VoucherService} rejects any voucher whose date falls in
 * a closed year (Req 4.3). {@link #closedAt} / {@link #closedBy} snapshot when and by whom the year
 * was closed.
 */
@Entity
@Table(name = "financial_years")
public class FinancialYear {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "label", nullable = false, length = 16)
    private String label;

    @Column(name = "closed", nullable = false)
    private boolean closed = false;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "closed_by", length = 100)
    private String closedBy;

    protected FinancialYear() {
        // Required by JPA.
    }

    public FinancialYear(LocalDate startDate, LocalDate endDate, String label) {
        this.startDate = startDate;
        this.endDate = endDate;
        this.label = label;
    }

    public Long getId() {
        return id;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public boolean isClosed() {
        return closed;
    }

    public void setClosed(boolean closed) {
        this.closed = closed;
    }

    public LocalDateTime getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(LocalDateTime closedAt) {
        this.closedAt = closedAt;
    }

    public String getClosedBy() {
        return closedBy;
    }

    public void setClosedBy(String closedBy) {
        this.closedBy = closedBy;
    }
}
