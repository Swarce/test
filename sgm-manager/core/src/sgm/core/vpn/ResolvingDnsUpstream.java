package sgm.core.vpn;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import sgm.core.Log;

/**
 * Answers the game's address lookups with the platform resolver of the real network
 * (Network.getAllByName on Android), which follows the phone's own DNS settings, Private DNS
 * included. Other record types go to the raw UDP forwarder.
 */
public final class ResolvingDnsUpstream implements PacketEngine.DnsUpstream {
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    static final int TYPE_A = 1;
    static final int TYPE_AAAA = 28;
    static final int RCODE_SERVFAIL = 2;

    private final Resolver resolver;
    private final PacketEngine.DnsUpstream fallback;

    public ResolvingDnsUpstream(Resolver resolver, PacketEngine.DnsUpstream fallback) {
        this.resolver = resolver;
        this.fallback = fallback;
    }

    @Override
    public byte[] query(byte[] q) throws IOException {
        if (q.length < 17 || (q[2] & 0x80) != 0 || PacketEngine.u16(q, 4) != 1) {
            return fallback == null ? null : fallback.query(q);
        }
        StringBuilder name = new StringBuilder();
        int i = 12;
        while (i < q.length && q[i] != 0) {
            int n = q[i] & 0xff;
            if (n > 63 || i + 1 + n > q.length) {
                return fallback == null ? null : fallback.query(q);
            }
            if (name.length() > 0) {
                name.append('.');
            }
            name.append(new String(q, i + 1, n, "ISO-8859-1"));
            i += n + 1;
        }
        if (i + 5 > q.length) {
            return null;
        }
        int type = PacketEngine.u16(q, i + 1);
        int qEnd = i + 5;
        if (type != TYPE_A && type != TYPE_AAAA) {
            return fallback == null ? null : fallback.query(q);
        }
        List<byte[]> addrs = new ArrayList<byte[]>();
        try {
            for (InetAddress a : resolver.resolve(name.toString())) {
                if ((type == TYPE_A && a instanceof Inet4Address) || (type == TYPE_AAAA && a instanceof Inet6Address)) {
                    addrs.add(a.getAddress());
                }
            }
        } catch (UnknownHostException e) {
            // also thrown without connectivity, so answer SERVFAIL (not cached) rather than NXDOMAIN
            Log.d("dns: " + name + " did not resolve: " + e.getMessage());
            return response(q, qEnd, type, addrs, RCODE_SERVFAIL);
        }
        return response(q, qEnd, type, addrs, 0);
    }

    static byte[] response(byte[] q, int qEnd, int type, List<byte[]> addrs, int rcode) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(q, 0, 2); // id
        out.write(0x80 | (q[2] & 0x01)); // QR, keep RD
        out.write(0x80 | rcode); // RA
        out.write(0);
        out.write(1); // one question
        out.write(addrs.size() >> 8);
        out.write(addrs.size());
        out.write(new byte[4], 0, 4); // no authority / additional records
        out.write(q, 12, qEnd - 12);
        for (byte[] a : addrs) {
            out.write(0xc0);
            out.write(0x0c); // name: pointer to the question
            out.write(type >> 8);
            out.write(type);
            out.write(0);
            out.write(1); // class IN
            out.write(new byte[]{0, 0, 0, 60}, 0, 4); // TTL 60 s
            out.write(0);
            out.write(a.length);
            out.write(a, 0, a.length);
        }
        return out.toByteArray();
    }
}
