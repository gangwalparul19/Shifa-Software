package com.shifa.oms.common;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

    private final ClientIpResolver resolver = new ClientIpResolver(
            List.of("127.0.0.1/32", "::1/128", "10.0.0.0/8"));

    @Test
    void ignoresForwardedHeaderFromUntrustedSocketPeer() {
        MockHttpServletRequest request = request("198.51.100.20", "203.0.113.9");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.20");
    }

    @Test
    void usesForwardedClientFromTrustedLoopbackProxy() {
        MockHttpServletRequest request = request("127.0.0.1", "203.0.113.9");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.9");
    }

    @Test
    void walksTrustedMultiHopChainFromApplicationTowardsClient() {
        MockHttpServletRequest request = request("10.2.3.4",
                "203.0.113.9, 10.8.0.4, 10.2.3.4");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.9");
    }

    @Test
    void malformedForwardedChainFallsBackToTrustedSocketPeer() {
        MockHttpServletRequest request = request("127.0.0.1", "not-an-ip, 203.0.113.9");

        assertThat(resolver.resolve(request)).isEqualTo("127.0.0.1");
    }

    @Test
    void invalidForwardedHeaderCannotCreateAnAttackerChosenBucket() {
        MockHttpServletRequest request = request("198.51.100.20", "203.0.113.9");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.20");
    }

    @Test
    void canonicalizesWhitespaceAndIpv4LeadingZeros() {
        MockHttpServletRequest request = request("127.0.0.1", " 203.0.113.009 ");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.9");
    }

    private static MockHttpServletRequest request(String remoteAddr, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        request.addHeader("X-Forwarded-For", forwardedFor);
        return request;
    }
}
