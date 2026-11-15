package ac.tree.testserver.iptree;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Utility for detecting "dirty" IPs that should never be written to the DB during /iptree db create.
 *
 * Supported dirty ranges (per plan):
 * IPv4: 10/8, 172.16/12, 192.168/16, 127/8, 169.254/16, 0.0.0.0
 * IPv6: ::1, fc00::/7, fe80::/10, ::
 * Plus IPv4-mapped / IPv4-compatible IPv6 variants.
 */
public final class IpUtils {

    public enum IPSkipReason {
        IPV4_PRIVATE_10,
        IPV4_PRIVATE_172_16_12,
        IPV4_PRIVATE_192_168_16,
        IPV4_LOOPBACK_127,
        IPV4_LINKLOCAL_169_254,
        IPV4_UNSPECIFIED_0,

        IPV6_LOOPBACK,
        IPV6_UNIQUE_LOCAL_FC00_7,
        IPV6_LINKLOCAL_FE80_10,
        IPV6_UNSPECIFIED
    }

    private IpUtils() {
    }

    /**
     * Checks whether the given string is a valid IPv4 or IPv6 literal without performing DNS lookups.
     */
    public static boolean isIpAddress(String rawIp) {
        String ip = normalize(rawIp);
        if (ip == null) return false;

        if (ip.indexOf(':') != -1) {
            for (int i = 0; i < ip.length(); i++) {
                char c = ip.charAt(i);
                boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
                if (!hex && c != ':' && c != '.') {
                    return false;
                }
            }
            try {
                InetAddress.getByName(ip);
                return true;
            } catch (UnknownHostException e) {
                return false;
            }
        }

        if (ip.indexOf('.') != -1) {
            return isValidIpv4Literal(ip);
        }

        return false;
    }

    private static boolean isValidIpv4Literal(String ip) {
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return false;
            int val = 0;
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (c < '0' || c > '9') return false;
                val = val * 10 + (c - '0');
            }
            if (val > 255) return false;
        }
        return true;
    }

    /**
     * @param rawIp IP string as parsed from log/YAML (may be IPv4, IPv6, or IPv4-mapped IPv6)
     * @return IPSkipReason if dirty; otherwise null.
     */
    public static IPSkipReason classify(String rawIp) {
        String ip = normalize(rawIp);
        if (ip == null) return null;

        InetAddress addr;
        try {
            addr = InetAddress.getByName(ip);
        } catch (UnknownHostException e) {
            return null;
        }

        byte[] bytes = addr.getAddress();
        if (bytes == null) return null;

        if (bytes.length == 4) {
            return classifyIpv4(bytes);
        }
        if (bytes.length == 16) {
            return classifyIpv6(bytes);
        }
        return null;
    }

    public static String formatSkippedMessage(IPSkipReason type, int count) {
        return "Skipped " + count + " entries from the " + describe(type) + ".";
    }

    public static String describe(IPSkipReason type) {
        switch (type) {
            case IPV4_PRIVATE_10:
                return "10.*.*.* private IP range";
            case IPV4_PRIVATE_172_16_12:
                return "172.16-31.*.* private IP range";
            case IPV4_PRIVATE_192_168_16:
                return "192.168.*.* private IP range";
            case IPV4_LOOPBACK_127:
                return "127.*.*.* loopback IP range";
            case IPV4_LINKLOCAL_169_254:
                return "169.254.*.* link-local IP range";
            case IPV4_UNSPECIFIED_0:
                return "0.0.0.0 unspecified IPv4 address";
            case IPV6_LOOPBACK:
                return "::1 IPv6 loopback address";
            case IPV6_UNIQUE_LOCAL_FC00_7:
                return "fc00::/7 IPv6 unique local IP range";
            case IPV6_LINKLOCAL_FE80_10:
                return "fe80::/10 IPv6 link-local IP range";
            case IPV6_UNSPECIFIED:
                return ":: unspecified IPv6 address";
            default:
                return "unknown dirty IP range";
        }
    }

    private static IPSkipReason classifyIpv4(byte[] b) {
        int b0 = b[0] & 0xFF;
        int b1 = b[1] & 0xFF;
        int b2 = b[2] & 0xFF;
        int b3 = b[3] & 0xFF;

        if (b0 == 10) return IPSkipReason.IPV4_PRIVATE_10;
        if (b0 == 172 && b1 >= 16 && b1 <= 31) return IPSkipReason.IPV4_PRIVATE_172_16_12;
        if (b0 == 192 && b1 == 168) return IPSkipReason.IPV4_PRIVATE_192_168_16;
        if (b0 == 127) return IPSkipReason.IPV4_LOOPBACK_127;
        if (b0 == 169 && b1 == 254) return IPSkipReason.IPV4_LINKLOCAL_169_254;
        if (b0 == 0 && b1 == 0 && b2 == 0 && b3 == 0) return IPSkipReason.IPV4_UNSPECIFIED_0;

        return null;
    }

    private static IPSkipReason classifyIpv6(byte[] b) {
        if (isAllZero(b)) return IPSkipReason.IPV6_UNSPECIFIED;
        if (isIpv6Loopback(b)) return IPSkipReason.IPV6_LOOPBACK;

        // IPv4-mapped (::ffff:a.b.c.d) and IPv4-compatible (::a.b.c.d) variants
        if (isIpv4Mapped(b) || isIpv4Compatible(b)) {
            byte[] v4 = new byte[]{b[12], b[13], b[14], b[15]};
            return classifyIpv4(v4);
        }

        // fc00::/7 (unique local)
        if ( (b[0] & (byte) 0xFE) == (byte) 0xFC ) {
            return IPSkipReason.IPV6_UNIQUE_LOCAL_FC00_7;
        }

        // fe80::/10 (link local)
        if ( (b[0] == (byte) 0xFE) && ((b[1] & (byte) 0xC0) == (byte) 0x80) ) {
            return IPSkipReason.IPV6_LINKLOCAL_FE80_10;
        }

        return null;
    }

    private static boolean isAllZero(byte[] b) {
        for (byte value : b) {
            if (value != 0) return false;
        }
        return true;
    }

    private static boolean isIpv6Loopback(byte[] b) {
        // ::1
        for (int i = 0; i < 15; i++) {
            if (b[i] != 0) return false;
        }
        return b[15] == 1;
    }

    private static boolean isIpv4Mapped(byte[] b) {
        // ::ffff:0:0/96
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) return false;
        }
        return b[10] == (byte) 0xFF && b[11] == (byte) 0xFF;
    }

    private static boolean isIpv4Compatible(byte[] b) {
        // ::0:0/96 (deprecated but still seen sometimes)
        for (int i = 0; i < 12; i++) {
            if (b[i] != 0) return false;
        }
        return true;
    }

    private static String normalize(String ip) {
        if (ip == null) return null;
        ip = ip.trim();
        if (ip.isEmpty()) return null;

        // Some sources may include leading slash or brackets
        if (ip.startsWith("/")) ip = ip.substring(1).trim();
        if (ip.startsWith("[") && ip.endsWith("]") && ip.length() > 2) {
            ip = ip.substring(1, ip.length() - 1).trim();
        }

        // Strip IPv6 zone index (e.g. fe80::1%eth0)
        int zoneIdx = ip.indexOf('%');
        if (zoneIdx != -1) {
            ip = ip.substring(0, zoneIdx).trim();
        }

        return ip.isEmpty() ? null : ip;
    }
}