package com.myvu.client.ai;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Locale;

/** Validates user-configured HTTP endpoints before any request is sent. */
public final class HttpEndpoint {
    private HttpEndpoint() {}

    /**
     * HTTPS is accepted everywhere. Cleartext HTTP is restricted to literal
     * private-network and loopback addresses so a mistyped public endpoint does
     * not send microphone audio, prompts, or credentials over the Internet.
     */
    public static URL parse(String value, String settingName) throws IOException {
        if (value == null || value.trim().isEmpty()) {
            throw new IOException(settingName + " is blank");
        }

        final URL url;
        try {
            url = new URL(value.trim());
        } catch (MalformedURLException e) {
            throw new IOException(settingName + " is not a valid URL: " + value, e);
        }

        String scheme = url.getProtocol().toLowerCase(Locale.US);
        if ("https".equals(scheme)) return url;
        if (!"http".equals(scheme)) {
            throw new IOException(settingName + " must use https, or http on a private LAN");
        }
        if (!isPrivateHost(url.getHost())) {
            throw new IOException(settingName
                    + " uses cleartext http with a public host; use https instead");
        }
        return url;
    }

    private static boolean isPrivateHost(String host) {
        String normalized = host.toLowerCase(Locale.US);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if ("localhost".equals(normalized) || "::1".equals(normalized)) return true;

        String[] octets = normalized.split("\\.");
        if (octets.length != 4) return isPrivateIpv6(normalized);
        try {
            int first = parseOctet(octets[0]);
            int second = parseOctet(octets[1]);
            parseOctet(octets[2]);
            parseOctet(octets[3]);
            return first == 10
                    || first == 127
                    || (first == 169 && second == 254)
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 168);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static int parseOctet(String value) {
        int parsed = Integer.parseInt(value);
        if (parsed < 0 || parsed > 255) throw new NumberFormatException("invalid IPv4 octet");
        return parsed;
    }

    private static boolean isPrivateIpv6(String host) {
        return host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe8")
                || host.startsWith("fe9") || host.startsWith("fea") || host.startsWith("feb");
    }
}
