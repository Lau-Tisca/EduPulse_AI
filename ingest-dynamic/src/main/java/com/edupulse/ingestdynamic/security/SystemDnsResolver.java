package com.edupulse.ingestdynamic.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.stereotype.Component;

/**
 * Default {@link DnsResolver} backed by JVM DNS resolution ({@link InetAddress#getAllByName}).
 */
@Component
public class SystemDnsResolver implements DnsResolver {

    @Override
    public InetAddress[] resolveAll(String host) throws UnknownHostException {
        return InetAddress.getAllByName(host);
    }
}
