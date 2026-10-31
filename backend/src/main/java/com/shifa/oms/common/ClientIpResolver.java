package com.shifa.oms.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the caller address without trusting forwarded headers from arbitrary
 * clients. Forwarded addresses are considered only when the socket peer is in
 * the explicitly configured trusted-proxy CIDR list.
 *
 * <p>The deployment is intentionally process-local and single-instance today:
 * the default trust list contains only loopback because Nginx and Spring Boot
 * run on the same host. If another proxy is introduced, add its exact address
 * or CIDR through {@code TRUSTED_PROXY_CIDRS}; do not trust all proxies by
 * default.</p>
 */
@Component
public class ClientIpResolver {

    private final List<Cidr> trustedProxies;

    @Autowired
    public ClientIpResolver(
            @Value("${app.security.trusted-proxies:127.0.0.1/32,::1/128}") String trustedProxyCidrs) {
        this.trustedProxies = parseTrustedProxies(trustedProxyCidrs);
    }

    /** Package-visible constructor for focused resolver tests. */
    ClientIpResolver(List<String> trustedProxyCidrs) {
        this.trustedProxies = parseTrustedProxies(String.join(",", trustedProxyCidrs));
    }

    /**
     * Returns a canonical IP address suitable for a limiter key. An X-Forwarded-For
     * header is used only from a trusted socket peer and only when its entire chain
     * is valid. Invalid or incomplete forwarding data falls back to the socket peer.
     */
    public String resolve(HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        String socketPeer = canonicalIp(request.getRemoteAddr());
        if (socketPeer == null || !isTrusted(socketPeer)) {
            return socketPeer == null ? "" : socketPeer;
        }

        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return socketPeer;
        }

        String[] tokens = forwarded.split(",", -1);
        List<String> chain = new ArrayList<>(tokens.length);
        for (String token : tokens) {
            String address = canonicalIp(token);
            if (address == null) {
                return socketPeer;
            }
            chain.add(address);
        }

        // Walk from the application-facing proxy toward the caller. A trusted
        // proxy may append another trusted proxy, but the first non-trusted hop
        // is the address to use for the caller bucket.
        for (int i = chain.size() - 1; i >= 0; i--) {
            String address = chain.get(i);
            if (!isTrusted(address)) {
                return address;
            }
        }
        return socketPeer;
    }

    private boolean isTrusted(String address) {
        InetAddress parsed = parseIp(address);
        if (parsed == null) {
            return false;
        }
        return trustedProxies.stream().anyMatch(cidr -> cidr.contains(parsed));
    }

    private static List<Cidr> parseTrustedProxies(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<Cidr> result = new ArrayList<>();
        for (String token : raw.split(",", -1)) {
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException("Trusted proxy CIDR must not be blank");
            }
            result.add(Cidr.parse(token.trim()));
        }
        return List.copyOf(result);
    }

    private static String canonicalIp(String raw) {
        InetAddress address = parseIp(raw);
        return address == null ? null : address.getHostAddress();
    }

    /** Parse only numeric IP syntax; never perform DNS resolution on untrusted input. */
    private static InetAddress parseIp(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty() || value.indexOf('%') >= 0) {
            return null;
        }
        if (value.indexOf(':') < 0) {
            String[] octets = value.split("\\.", -1);
            if (octets.length != 4) {
                return null;
            }
            byte[] bytes = new byte[4];
            for (int i = 0; i < octets.length; i++) {
                if (!octets[i].matches("\\d{1,3}")) {
                    return null;
                }
                try {
                    int number = Integer.parseInt(octets[i]);
                    if (number > 255) {
                        return null;
                    }
                    bytes[i] = (byte) number;
                } catch (NumberFormatException ex) {
                    return null;
                }
            }
            try {
                return InetAddress.getByAddress(bytes);
            } catch (UnknownHostException impossible) {
                return null;
            }
        }
        if (!value.matches("[0-9A-Fa-f:.]+")) {
            return null;
        }
        try {
            // The character whitelist above prevents DNS names and zone IDs.
            return InetAddress.getByName(value);
        } catch (UnknownHostException ex) {
            return null;
        }
    }

    private record Cidr(byte[] network, int prefixLength) {
        private static Cidr parse(String raw) {
            String[] parts = raw.split("/", -1);
            if (parts.length > 2 || parts[0].isBlank()) {
                throw new IllegalArgumentException("Invalid trusted proxy CIDR: " + raw);
            }
            InetAddress address = parseIp(parts[0].trim());
            if (address == null) {
                throw new IllegalArgumentException("Invalid trusted proxy address: " + raw);
            }
            int maxBits = address.getAddress().length * 8;
            int prefix = parts.length == 1 ? maxBits : parsePrefix(parts[1], maxBits, raw);
            return new Cidr(mask(address.getAddress(), prefix), prefix);
        }

        private static int parsePrefix(String raw, int maxBits, String original) {
            try {
                int prefix = Integer.parseInt(raw.trim());
                if (prefix < 0 || prefix > maxBits) {
                    throw new IllegalArgumentException("Invalid trusted proxy CIDR: " + original);
                }
                return prefix;
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Invalid trusted proxy CIDR: " + original, ex);
            }
        }

        private boolean contains(InetAddress address) {
            byte[] candidate = address.getAddress();
            if (candidate.length != network.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != network[i]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xFF << (8 - remainingBits);
            return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
        }

        private static byte[] mask(byte[] address, int prefixLength) {
            byte[] network = address.clone();
            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;
            if (fullBytes < network.length) {
                if (remainingBits != 0) {
                    network[fullBytes] = (byte) (network[fullBytes] & (0xFF << (8 - remainingBits)));
                    fullBytes++;
                }
                for (int i = fullBytes; i < network.length; i++) {
                    network[i] = 0;
                }
            }
            return network;
        }
    }
}
