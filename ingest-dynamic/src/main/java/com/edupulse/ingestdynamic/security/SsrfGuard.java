package com.edupulse.ingestdynamic.security;

import com.edupulse.ingestdynamic.config.IngestDynamicProperties;
import com.edupulse.ingestdynamic.exception.InvalidUrlException;
import com.edupulse.ingestdynamic.exception.RemoteFetchException;
import com.edupulse.ingestdynamic.exception.SsrfBlockedException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Enforces SSRF guardrails before any outbound HTTP connection is opened:
 * <ol>
 *   <li>Only {@code https://} URLs without embedded credentials are permitted.</li>
 *   <li>Host must match {@code APP_INGEST_ALLOWED_DOMAINS} (exact domain or subdomain).</li>
 *   <li>When {@code APP_INGEST_BLOCK_PRIVATE_NETWORKS=true}, DNS is resolved up front and any
 *       loopback, private (RFC 1918 / ULA), link-local (cloud metadata 169.254/16), CGNAT,
 *       unspecified, multicast, or IPv4-mapped internal IPv6 address is rejected.</li>
 * </ol>
 */
@Component
public class SsrfGuard {

    private static final Logger log = LoggerFactory.getLogger(SsrfGuard.class);

    private final IngestDynamicProperties properties;
    private final DnsResolver dnsResolver;

    public SsrfGuard(IngestDynamicProperties properties, DnsResolver dnsResolver) {
        this.properties = properties;
        this.dnsResolver = dnsResolver;
    }

    /**
     * Validates the raw URL and returns the parsed {@link URI} if it passes all SSRF checks.
     */
    public URI validate(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new InvalidUrlException("URL must not be blank");
        }
        URI uri;
        try {
            uri = new URI(rawUrl.trim());
        } catch (URISyntaxException ex) {
            throw new InvalidUrlException("Malformed URL: " + sanitize(rawUrl), ex);
        }
        return validate(uri);
    }

    /**
     * Validates a parsed {@link URI} (called for the initial URL and every HTTP redirect hop).
     */
    public URI validate(URI uri) {
        if (uri == null) {
            throw new InvalidUrlException("URL must not be null");
        }

        String scheme = uri.getScheme();
        if (scheme == null || !"https".equalsIgnoreCase(scheme)) {
            log.warn("SSRF blocked non-HTTPS URL: scheme={}, uri={}", sanitize(scheme), sanitize(uri.toString()));
            throw new SsrfBlockedException("Only HTTPS URLs are permitted for dynamic ingestion");
        }

        if (uri.getRawUserInfo() != null) {
            log.warn("SSRF blocked URL with embedded credentials: host={}", sanitize(uri.getHost()));
            throw new SsrfBlockedException("URLs containing userinfo credentials are not permitted");
        }

        String host = normalizeHost(uri.getHost());
        if (host == null || host.isEmpty()) {
            throw new InvalidUrlException("URL must include a valid host");
        }

        List<String> allowedDomains = properties.ingest().allowedDomains();
        if (!isDomainAllowed(host, allowedDomains)) {
            log.warn("SSRF blocked disallowed domain: host={}, allowed={}", sanitize(host), allowedDomains);
            throw new SsrfBlockedException("Domain '" + host + "' is not in APP_INGEST_ALLOWED_DOMAINS");
        }

        if (properties.ingest().blockPrivateNetworks()) {
            validateResolvedAddresses(host);
        }

        return uri;
    }

    private void validateResolvedAddresses(String host) {
        InetAddress[] addresses;
        try {
            addresses = dnsResolver.resolveAll(host);
        } catch (UnknownHostException ex) {
            log.warn("DNS resolution failed for host={}: {}", sanitize(host), sanitize(ex.getMessage()));
            throw new RemoteFetchException(HttpStatus.BAD_GATEWAY, "Failed to resolve host: " + host, ex);
        }

        if (addresses == null || addresses.length == 0) {
            throw new RemoteFetchException(HttpStatus.BAD_GATEWAY, "DNS returned no addresses for host: " + host);
        }

        for (InetAddress address : addresses) {
            if (isBlockedAddress(address)) {
                log.warn("SSRF blocked private/internal IP resolution: host={}, ip={}",
                        sanitize(host), address.getHostAddress());
                throw new SsrfBlockedException(
                        "Resolved IP address for host '" + host + "' is in a blocked private or internal network range");
            }
        }
    }

    static boolean isDomainAllowed(String normalizedHost, List<String> allowedDomains) {
        for (String allowed : allowedDomains) {
            if (normalizedHost.equals(allowed) || normalizedHost.endsWith("." + allowed)) {
                return true;
            }
        }
        return false;
    }

    static boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }

        byte[] raw = address.getAddress();
        if (address instanceof Inet4Address || raw.length == 4) {
            return isBlockedIpv4Bytes(raw);
        }
        if (address instanceof Inet6Address || raw.length == 16) {
            return isBlockedIpv6Bytes(raw);
        }
        return true;
    }

    private static boolean isBlockedIpv4Bytes(byte[] ip) {
        int b0 = ip[0] & 0xFF;
        int b1 = ip[1] & 0xFF;
        int b2 = ip[2] & 0xFF;

        // 0.0.0.0/8 ("this network")
        if (b0 == 0) {
            return true;
        }
        // 10.0.0.0/8 (RFC 1918 private)
        if (b0 == 10) {
            return true;
        }
        // 100.64.0.0/10 (RFC 6598 CGNAT / shared address space)
        if (b0 == 100 && (b1 & 0xC0) == 64) {
            return true;
        }
        // 127.0.0.0/8 (loopback)
        if (b0 == 127) {
            return true;
        }
        // 169.254.0.0/16 (link-local & cloud metadata 169.254.169.254)
        if (b0 == 169 && b1 == 254) {
            return true;
        }
        // 172.16.0.0/12 (RFC 1918 private)
        if (b0 == 172 && (b1 & 0xF0) == 16) {
            return true;
        }
        // 192.0.0.0/24 (IETF protocol assignments) & 192.0.2.0/24 (TEST-NET-1)
        if (b0 == 192 && b1 == 0 && (b2 == 0 || b2 == 2)) {
            return true;
        }
        // 192.168.0.0/16 (RFC 1918 private)
        if (b0 == 192 && b1 == 168) {
            return true;
        }
        // 198.18.0.0/15 (benchmarking)
        if (b0 == 198 && (b1 == 18 || b1 == 19)) {
            return true;
        }
        // 198.51.100.0/24 (TEST-NET-2) & 203.0.113.0/24 (TEST-NET-3)
        if ((b0 == 198 && b1 == 51 && b2 == 100) || (b0 == 203 && b1 == 0 && b2 == 113)) {
            return true;
        }
        // 224.0.0.0/4 (multicast) and 240.0.0.0/4 (reserved / broadcast 255.255.255.255)
        return b0 >= 224;
    }

    private static boolean isBlockedIpv6Bytes(byte[] ip) {
        int b0 = ip[0] & 0xFF;
        int b1 = ip[1] & 0xFF;

        // Unique Local Addresses fc00::/7 (fc00:: - fdff::)
        if ((b0 & 0xFE) == 0xFC) {
            return true;
        }
        // Link-local fe80::/10
        if (b0 == 0xFE && (b1 & 0xC0) == 0x80) {
            return true;
        }
        // IPv4-mapped IPv6 ::ffff:0:0/96
        boolean ipv4Mapped = true;
        for (int i = 0; i < 10; i++) {
            if (ip[i] != 0) {
                ipv4Mapped = false;
                break;
            }
        }
        if (ipv4Mapped && (ip[10] & 0xFF) == 0xFF && (ip[11] & 0xFF) == 0xFF) {
            byte[] mappedIpv4 = new byte[]{ip[12], ip[13], ip[14], ip[15]};
            return isBlockedIpv4Bytes(mappedIpv4);
        }
        return false;
    }

    private static String normalizeHost(String host) {
        if (host == null) {
            return null;
        }
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ');
    }
}
