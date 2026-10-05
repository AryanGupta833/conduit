package com.aryan.conduit.plugin.security;

import com.aryan.conduit.plugin.sdk.PluginConfigurationException;

import java.net.InetAddress;
import java.net.URI;

/** Rejects local and non-public network destinations unless a workflow opts in explicitly. */
public final class SsrfAddressPolicy {
    private SsrfAddressPolicy() { }

    public static void validate(URI uri, boolean allowPrivateNetwork) {
        if (uri == null || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null)
            throw new PluginConfigurationException("HTTP destination is invalid or contains credentials");
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
            throw new PluginConfigurationException("HTTP destination must use HTTP or HTTPS");
        if (uri.getPort() < -1 || uri.getPort() == 0 || uri.getPort() > 65535)
            throw new PluginConfigurationException("HTTP destination port is invalid");
        validateHost(uri.getHost(), allowPrivateNetwork);
    }

    public static void validateHost(String host, boolean allowPrivateNetwork) {
        if (host == null || host.isBlank()) throw new PluginConfigurationException("Network destination host is required");
        if (allowPrivateNetwork) return;
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) throw new PluginConfigurationException("HTTP destination did not resolve");
            for (InetAddress address : addresses) {
                if (isNonPublic(address)) throw new PluginConfigurationException("HTTP destination resolves to a local or non-public address");
            }
        } catch (java.net.UnknownHostException e) {
            throw new PluginConfigurationException("HTTP destination host could not be resolved", e);
        }
    }

    public static boolean isNonPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return true;
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) {
            int first = Byte.toUnsignedInt(bytes[0]);
            return (first & 0xfe) == 0xfc || (first == 0xfe && (Byte.toUnsignedInt(bytes[1]) & 0xc0) == 0x80);
        }
        if (bytes.length == 4) {
            int a = Byte.toUnsignedInt(bytes[0]), b = Byte.toUnsignedInt(bytes[1]);
            return a == 0 || a == 10 || a == 127 || (a == 100 && b >= 64 && b <= 127)
                    || (a == 169 && b == 254) || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && b == 168) || (a == 192 && b == 0) || a >= 224
                    || (a == 198 && (b == 18 || b == 19 || b == 51)) || (a == 203 && b == 0 && Byte.toUnsignedInt(bytes[2]) == 113);
        }
        return true;
    }
}
