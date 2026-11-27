package com.edupulse.ingestdynamic.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.edupulse.ingestdynamic.config.IngestDynamicProperties;
import com.edupulse.ingestdynamic.exception.InvalidUrlException;
import com.edupulse.ingestdynamic.exception.RemoteFetchException;
import com.edupulse.ingestdynamic.exception.SsrfBlockedException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SsrfGuardTest {

    private static IngestDynamicProperties defaultProps() {
        return new IngestDynamicProperties(
                512,
                64,
                IngestDynamicProperties.Watcher.defaults(),
                IngestDynamicProperties.Ingest.defaults(),
                new IngestDynamicProperties.Webhook("test-secret")
        );
    }

    private static SsrfGuard guardWithIp(String... ipLiterals) {
        DnsResolver resolver = host -> {
            InetAddress[] out = new InetAddress[ipLiterals.length];
            for (int i = 0; i < ipLiterals.length; i++) {
                out[i] = InetAddress.getByName(ipLiterals[i]);
            }
            return out;
        };
        return new SsrfGuard(defaultProps(), resolver);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://kubernetes.io/docs/concepts/overview/what-is-kubernetes/",
            "https://v1-30.docs.kubernetes.io/docs/home/",
            "https://raw.githubusercontent.com/kubernetes/website/main/README.md",
            "https://github.com/kubernetes/kubernetes/blob/master/README.md",
            "https://www.cncf.io/reports/cloud-native-security-whitepaper/"
    })
    void allowsHttpsAllowedDomainsAndSubdomainsResolvingToPublicIp(String url) {
        SsrfGuard guard = guardWithIp("93.184.216.34");
        URI validated = guard.validate(url);
        assertThat(validated.toString()).isEqualTo(url);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://kubernetes.io/docs/home/",
            "ftp://kubernetes.io/docs/home/",
            "file:///etc/passwd",
            "https://user:pass@kubernetes.io/docs/home/",
            "https://kubernetes.io:8080/docs/home/",
            "https://kubernetes.io:8443/docs/home/"
    })
    void blocksNonHttpsEmbeddedCredentialsAndNonStandardPorts(String url) {
        SsrfGuard guard = guardWithIp("93.184.216.34");
        assertThatThrownBy(() -> guard.validate(url))
                .isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    void allowsExplicitHttpsPort443AndReturnsValidatedAddressesForPinning() {
        SsrfGuard guard = guardWithIp("93.184.216.34");
        SsrfGuard.ValidatedTarget target = guard.validateAndResolve("https://kubernetes.io:443/docs/");
        assertThat(target.uri().getHost()).isEqualTo("kubernetes.io");
        assertThat(target.resolvedAddresses())
                .extracting(InetAddress::getHostAddress)
                .containsExactly("93.184.216.34");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com/page",
            "https://evil-kubernetes.io/docs",
            "https://kubernetes.io.attacker.com/docs",
            "https://169.254.169.254/latest/meta-data/"
    })
    void blocksDisallowedDomainsAndSuffixSpoofing(String url) {
        SsrfGuard guard = guardWithIp("93.184.216.34");
        assertThatThrownBy(() -> guard.validate(url))
                .isInstanceOf(SsrfBlockedException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "127.0.0.1",
            "127.255.255.254",
            "10.0.0.1",
            "172.16.0.1",
            "172.31.255.254",
            "192.168.1.10",
            "169.254.169.254",
            "100.64.0.1",
            "0.0.0.0",
            "224.0.0.1",
            "::1",
            "fe80::1",
            "fc00::1",
            "fd12:3456::1",
            "::ffff:127.0.0.1",
            "::ffff:169.254.169.254",
            "::127.0.0.1",
            "::169.254.169.254",
            "64:ff9b::169.254.169.254",
            "64:ff9b:1::1",
            "2002:a9fe:a9fe::1",
            "2001:0000:4136:e378::1",
            "2001:db8::1",
            "100::1"
    })
    void blocksPrivateLoopbackLinkLocalAndInternalIps(String ip) {
        SsrfGuard guard = guardWithIp(ip);
        assertThatThrownBy(() -> guard.validate("https://kubernetes.io/docs/"))
                .isInstanceOf(SsrfBlockedException.class)
                .hasMessageContaining("blocked private or internal");
    }

    @Test
    void blocksWhenAnyResolvedIpInMultiRecordDnsIsPrivate() {
        SsrfGuard guard = guardWithIp("93.184.216.34", "169.254.169.254");
        assertThatThrownBy(() -> guard.validate("https://kubernetes.io/docs/"))
                .isInstanceOf(SsrfBlockedException.class);
    }

    @Test
    void rejectsBlankAndMalformedUrls() {
        SsrfGuard guard = guardWithIp("93.184.216.34");
        assertThatThrownBy(() -> guard.validate("   ")).isInstanceOf(InvalidUrlException.class);
        assertThatThrownBy(() -> guard.validate("https://bad host/path")).isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void failsClosedWhenDnsResolutionFails() {
        SsrfGuard guard = new SsrfGuard(defaultProps(), host -> {
            throw new UnknownHostException("nxdomain");
        });
        assertThatThrownBy(() -> guard.validate("https://kubernetes.io/docs/"))
                .isInstanceOf(RemoteFetchException.class);
    }

    @Test
    void allowsPrivateIpOnlyWhenBlockPrivateNetworksIsDisabled() {
        IngestDynamicProperties relaxed = new IngestDynamicProperties(
                512,
                64,
                IngestDynamicProperties.Watcher.defaults(),
                new IngestDynamicProperties.Ingest(1024, 5000, false, List.of("kubernetes.io")),
                new IngestDynamicProperties.Webhook("secret")
        );
        SsrfGuard guard = new SsrfGuard(relaxed, host -> new InetAddress[]{InetAddress.getByName("127.0.0.1")});
        URI uri = guard.validate("https://kubernetes.io/docs/");
        assertThat(uri.getHost()).isEqualTo("kubernetes.io");
    }
}
