package sgm.core.vpn;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import sgm.core.Log;

/**
 * IPv4 packet handling for the manager's VPN interface (only the game's traffic is routed into it,
 * and only to a few virtual addresses):
 *
 * <ul>
 * <li>DNS to the virtual resolver: the redirected hostnames get a virtual IP; every other name
 * is forwarded to the real resolver.</li>
 * <li>TCP to a virtual IP is "reflected" onto a local listener by rewriting addresses: the game's
 * A:x -&gt; V:443 becomes V:x -&gt; A:P (P = local server port) and is written back to the
 * interface, so the kernel delivers it to the server socket. The server's replies A:P -&gt; V:x
 * come back through the interface and are rewritten to V:443 -&gt; A:x. No per-connection state
 * is needed: the virtual IP and listener port identify the original destination.</li>
 * </ul>
 */
public final class PacketEngine {
    public interface Output {
        void write(byte[] packet, int off, int len) throws IOException;
    }

    /** Forwards a raw DNS query to a real resolver; returns the raw response or null. */
    public interface DnsUpstream {
        byte[] query(byte[] q) throws IOException;
    }

    static final int TCP = 6;
    static final int UDP = 17;

    private final Output out;
    private final DnsUpstream upstream;
    private final int dnsAddr;
    /** lower-case hostname -&gt; virtual IPv4 address */
    private final Map<String, Integer> hosts = new HashMap<String, Integer>();
    /** (virtual ip, original port) -&gt; local listener port */
    private final Map<Long, Integer> forward = new HashMap<Long, Integer>();
    /** (virtual ip, listener port) -&gt; original port */
    private final Map<Long, Integer> reverse = new HashMap<Long, Integer>();
    private final ExecutorService dnsPool = Executors.newFixedThreadPool(4, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "sgm-dns");
            t.setDaemon(true);
            return t;
        }
    });
    public volatile long packetsIn;
    public volatile long reflected;
    public volatile long dnsAnswered;

    public PacketEngine(Output out, DnsUpstream upstream, int dnsAddr) {
        this.out = out;
        this.upstream = upstream;
        this.dnsAddr = dnsAddr;
    }

    public synchronized void mapHost(String host, int virtualIp) {
        hosts.put(host.toLowerCase(Locale.ROOT), virtualIp);
    }

    public synchronized void mapPort(int virtualIp, int originalPort, int listenerPort) {
        forward.put(key(virtualIp, originalPort), listenerPort);
        reverse.put(key(virtualIp, listenerPort), originalPort);
    }

    public void shutdown() {
        dnsPool.shutdownNow();
    }

    private static long key(int ip, int port) {
        return ((ip & 0xffffffffL) << 16) | (port & 0xffff);
    }

    public static int ip(String dotted) {
        String[] p = dotted.split("\\.");
        return (Integer.parseInt(p[0]) << 24) | (Integer.parseInt(p[1]) << 16) | (Integer.parseInt(p[2]) << 8) | Integer.parseInt(p[3]);
    }

    public static String ipString(int ip) {
        return ((ip >>> 24) & 0xff) + "." + ((ip >>> 16) & 0xff) + "." + ((ip >>> 8) & 0xff) + "." + (ip & 0xff);
    }

    // ---- packet entry point ---------------------------------------------------------------

    /** Handles one packet read from the interface (the buffer may be reused by the caller). */
    public void handle(byte[] buf, int len) throws IOException {
        packetsIn++;
        if (len < 20 || (buf[0] >> 4 & 0xf) != 4) {
            return; // IPv6 is never routed to the interface
        }
        int ihl = (buf[0] & 0xf) * 4;
        int total = u16(buf, 2);
        if (total > len || total < ihl + 8 || (u16(buf, 6) & 0x3fff) != 0) {
            return; // truncated or fragmented
        }
        int proto = buf[9] & 0xff;
        int src = i32(buf, 12);
        int dst = i32(buf, 16);
        if (proto == UDP) {
            if (dst == dnsAddr && u16(buf, ihl + 2) == 53) {
                byte[] copy = new byte[total];
                System.arraycopy(buf, 0, copy, 0, total);
                dns(copy, ihl, src, dst);
            }
            return;
        }
        if (proto != TCP || total < ihl + 20) {
            return;
        }
        int sport = u16(buf, ihl);
        int dport = u16(buf, ihl + 2);
        Integer listener;
        Integer original;
        synchronized (this) {
            listener = forward.get(key(dst, dport));
            original = listener == null ? reverse.get(key(dst, sport)) : null;
        }
        byte[] p = new byte[total];
        System.arraycopy(buf, 0, p, 0, total);
        if (listener != null) {
            // game -> virtual:port  =>  virtual:x -> game address:listener
            put32(p, 12, dst);
            put32(p, 16, src);
            put16(p, ihl + 2, listener);
        } else if (original != null) {
            // server reply  =>  virtual:original -> game address:x
            put32(p, 12, dst);
            put32(p, 16, src);
            put16(p, ihl, original);
        } else {
            if (isVirtual(dst) && (p[ihl + 13] & 0x02) != 0 && (p[ihl + 13] & 0x10) == 0) {
                reset(p, ihl, total); // SYN to a port nobody serves: refuse quickly instead of timing out
            }
            return;
        }
        tcpChecksum(p, ihl, total);
        ipChecksum(p, ihl);
        reflected++;
        out.write(p, 0, total);
    }

    /** Virtual addresses (and the resolver: Android's Private DNS probes it on TCP 853). */
    private synchronized boolean isVirtual(int ip) {
        return ip == dnsAddr || hosts.containsValue(ip);
    }

    private void reset(byte[] syn, int ihl, int total) throws IOException {
        byte[] r = new byte[40];
        r[0] = 0x45;
        put16(r, 2, 40);
        r[8] = 64;
        r[9] = TCP;
        put32(r, 12, i32(syn, 16));
        put32(r, 16, i32(syn, 12));
        put16(r, 20, u16(syn, ihl + 2));
        put16(r, 22, u16(syn, ihl));
        put32(r, 24, 0);
        put32(r, 28, i32(syn, ihl + 4) + 1);
        r[32] = 0x50;
        r[33] = 0x14; // RST, ACK
        tcpChecksum(r, 20, 40);
        ipChecksum(r, 20);
        out.write(r, 0, 40);
    }

    // ---- DNS --------------------------------------------------------------------------------

    private void dns(final byte[] p, final int ihl, final int src, final int dst) throws IOException {
        final int sport = u16(p, ihl);
        final int qOff = ihl + 8;
        final int qLen = p.length - qOff;
        if (qLen < 12) {
            return;
        }
        final byte[] q = new byte[qLen];
        System.arraycopy(p, qOff, q, 0, qLen);
        String name = null;
        int qtype = 0;
        int qEnd = 0;
        try {
            if ((q[2] & 0x80) == 0 && u16(q, 4) == 1) {
                StringBuilder sb = new StringBuilder();
                int i = 12;
                while (q[i] != 0) {
                    int n = q[i] & 0xff;
                    if (n > 63) {
                        throw new IOException("compressed question");
                    }
                    if (sb.length() > 0) {
                        sb.append('.');
                    }
                    sb.append(new String(q, i + 1, n, "ISO-8859-1"));
                    i += n + 1;
                }
                qtype = u16(q, i + 1);
                qEnd = i + 5;
                name = sb.toString().toLowerCase(Locale.ROOT);
            }
        } catch (Exception e) {
            name = null;
        }
        Integer v;
        synchronized (this) {
            v = name == null ? null : hosts.get(name);
        }
        if (v != null) {
            dnsAnswered++;
            reply(src, sport, dst, answer(q, qEnd, qtype == 1 ? v : null));
            return;
        }
        final String logName = name;
        dnsPool.execute(new Runnable() {
            @Override
            public void run() {
                byte[] r = null;
                try {
                    r = upstream == null ? null : upstream.query(q);
                } catch (IOException e) {
                    Log.d("dns upstream failed for " + logName + ": " + e);
                }
                if (r == null) {
                    r = servfail(q);
                }
                try {
                    reply(src, sport, dst, r);
                } catch (IOException e) {
                    Log.d("dns reply failed: " + e);
                }
            }
        });
    }

    /** Response with one A record (or none, for AAAA and other types: NOERROR/NODATA). */
    static byte[] answer(byte[] q, int qEnd, Integer ip) {
        int n = qEnd + (ip != null ? 16 : 0);
        byte[] r = new byte[n];
        System.arraycopy(q, 0, r, 0, qEnd);
        r[2] = (byte) (0x84 | (q[2] & 0x01)); // QR, AA, keep RD
        r[3] = (byte) 0x80; // RA, NOERROR
        put16(r, 6, ip != null ? 1 : 0);
        put16(r, 8, 0);
        put16(r, 10, 0);
        if (ip != null) {
            int i = qEnd;
            put16(r, i, 0xc00c);
            put16(r, i + 2, 1);
            put16(r, i + 4, 1);
            put32(r, i + 6, 60);
            put16(r, i + 10, 4);
            put32(r, i + 12, ip);
        }
        return r;
    }

    static byte[] servfail(byte[] q) {
        byte[] r = new byte[12];
        System.arraycopy(q, 0, r, 0, 12);
        r[2] = (byte) (0x80 | (q[2] & 0x01));
        r[3] = (byte) 0x82;
        put16(r, 4, 0);
        put16(r, 6, 0);
        put16(r, 8, 0);
        put16(r, 10, 0);
        return r;
    }

    private void reply(int to, int toPort, int from, byte[] payload) throws IOException {
        int total = 28 + payload.length;
        byte[] p = new byte[total];
        p[0] = 0x45;
        put16(p, 2, total);
        p[8] = 64;
        p[9] = UDP;
        put32(p, 12, from);
        put32(p, 16, to);
        put16(p, 20, 53);
        put16(p, 22, toPort);
        put16(p, 24, 8 + payload.length);
        System.arraycopy(payload, 0, p, 28, payload.length);
        udpChecksum(p, 20, total);
        ipChecksum(p, 20);
        out.write(p, 0, total);
    }

    // ---- checksums / byte helpers --------------------------------------------------------------

    static int u16(byte[] b, int o) {
        return ((b[o] & 0xff) << 8) | (b[o + 1] & 0xff);
    }

    static int i32(byte[] b, int o) {
        return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16) | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
    }

    static void put16(byte[] b, int o, int v) {
        b[o] = (byte) (v >> 8);
        b[o + 1] = (byte) v;
    }

    static void put32(byte[] b, int o, int v) {
        b[o] = (byte) (v >> 24);
        b[o + 1] = (byte) (v >> 16);
        b[o + 2] = (byte) (v >> 8);
        b[o + 3] = (byte) v;
    }

    private static long sum(byte[] b, int off, int len, long acc) {
        int i = off;
        int end = off + len;
        while (i + 1 < end) {
            acc += ((b[i] & 0xff) << 8) | (b[i + 1] & 0xff);
            i += 2;
        }
        if (i < end) {
            acc += (b[i] & 0xff) << 8;
        }
        return acc;
    }

    private static int fold(long acc) {
        while ((acc >> 16) != 0) {
            acc = (acc & 0xffff) + (acc >> 16);
        }
        return (int) (~acc & 0xffff);
    }

    static void ipChecksum(byte[] p, int ihl) {
        put16(p, 10, 0);
        put16(p, 10, fold(sum(p, 0, ihl, 0)));
    }

    private static long pseudo(byte[] p, int proto, int len) {
        long acc = sum(p, 12, 8, 0);
        return acc + proto + len;
    }

    static void tcpChecksum(byte[] p, int ihl, int total) {
        put16(p, ihl + 16, 0);
        int len = total - ihl;
        put16(p, ihl + 16, fold(sum(p, ihl, len, pseudo(p, TCP, len))));
    }

    static void udpChecksum(byte[] p, int ihl, int total) {
        put16(p, ihl + 6, 0);
        int len = total - ihl;
        int c = fold(sum(p, ihl, len, pseudo(p, UDP, len)));
        put16(p, ihl + 6, c == 0 ? 0xffff : c);
    }
}
