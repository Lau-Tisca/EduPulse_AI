package com.edupulse.ingestdynamic.security;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Abstraction over DNS resolution so {@link SsrfGuard} can be unit-tested deterministically
 * without live network/DNS calls.
 */
@FunctionalInterface
public interface DnsResolver {

    InetAddress[] resolveAll(String host) throws UnknownHostException;
}
