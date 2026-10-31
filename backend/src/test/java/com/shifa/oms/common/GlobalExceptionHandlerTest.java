package com.shifa.oms.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    @Test
    void paymentUtrUniqueViolationBecomesSafeConflictResponse() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler(new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/orders");
        DataIntegrityViolationException duplicate = new DataIntegrityViolationException(
                "Duplicate entry for key 'uq_payments_receiving_account_utr'");

        var response = handler.handleDataIntegrityViolation(duplicate, request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("PAYMENT_UTR_DUPLICATE");
        assertThat(response.getBody().message()).doesNotContain("uq_payments");
    }

    @Test
    void unrelatedDataIntegrityViolationRemainsGeneric() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler(new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/orders");

        var response = handler.handleDataIntegrityViolation(
                new DataIntegrityViolationException("some other constraint"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
    }
}
