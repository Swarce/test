package sgm.core.vpn;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;

/** Forwards DNS queries to real resolvers over UDP, from sockets kept outside the VPN. */
public final class UdpDnsUpstream implements PacketEngine.DnsUpstream {
    /** Exempts a socket from the VPN (VpnService.protect on Android; no-op elsewhere). */
    public interface Protector {
        boolean protect(DatagramSocket s);
    }

    private final List<InetAddress> servers;
    private final Protector protector;

    public UdpDnsUpstream(List<InetAddress> servers, Protector protector) {
        this.servers = servers;
        this.protector = protector;
    }

    @Override
    public byte[] query(byte[] q) throws IOException {
        IOException last = null;
        for (InetAddress server : servers) {
            DatagramSocket s = new DatagramSocket();
            try {
                if (protector != null && !protector.protect(s)) {
                    throw new IOException("could not protect DNS socket");
                }
                s.setSoTimeout(2500);
                s.send(new DatagramPacket(q, q.length, server, 53));
                byte[] buf = new byte[4096];
                while (true) {
                    DatagramPacket r = new DatagramPacket(buf, buf.length);
                    s.receive(r);
                    // match the transaction id
                    if (r.getLength() >= 12 && buf[0] == q[0] && buf[1] == q[1]) {
                        byte[] out = new byte[r.getLength()];
                        System.arraycopy(buf, 0, out, 0, out.length);
                        return out;
                    }
                }
            } catch (IOException e) {
                last = e;
            } finally {
                s.close();
            }
        }
        if (last != null) {
            throw last;
        }
        return null;
    }
}
