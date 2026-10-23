package sgm.core.vpn;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import sgm.core.Server;

/**
 * Runs the VPN packet engine against a Linux TUN device that tools/tun_shuttle.py relays over
 * stdin/stdout (2-byte length-prefixed packets), to test DNS + TCP reflection on a real kernel.
 * args: content-dir data-dir [fake-cdn-port]
 */
public final class TunHarness {
    public static void main(String[] args) throws Exception {
        Server server = Server.open(new File(args[0]), new File(args[1]), null);
        // the "real CDN" is a local fake on this port (tools/tun_shuttle.py), 0 = no internet
        final int cdnPort = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        Gateway gw = new Gateway(server, new File(args[1]), true, new CdnCache.Opener() {
            @Override
            public HttpURLConnection open(URL url) throws IOException {
                if (cdnPort == 0) {
                    throw new IOException("no internet connection");
                }
                return (HttpURLConnection) new URL("http://127.0.0.1:" + cdnPort + url.getFile()).openConnection(java.net.Proxy.NO_PROXY);
            }
        });
        gw.start(Gateway.TUN_ADDR);
        final DataOutputStream out = new DataOutputStream(System.out);
        List<InetAddress> resolvers = new ArrayList<InetAddress>();
        BufferedReader r = new BufferedReader(new FileReader("/etc/resolv.conf"));
        for (String line; (line = r.readLine()) != null; ) {
            if (line.startsWith("nameserver ")) {
                resolvers.add(InetAddress.getByName(line.substring(11).trim()));
            }
        }
        r.close();
        PacketEngine engine = gw.engine(new PacketEngine.Output() {
            @Override
            public void write(byte[] p, int off, int len) throws IOException {
                synchronized (out) {
                    out.writeShort(len);
                    out.write(p, off, len);
                    out.flush();
                }
            }
        }, new ResolvingDnsUpstream(new ResolvingDnsUpstream.Resolver() {
            @Override
            public InetAddress[] resolve(String host) throws java.net.UnknownHostException {
                return InetAddress.getAllByName(host);
            }
        }, new UdpDnsUpstream(resolvers, null)));
        System.err.println("READY");
        DataInputStream in = new DataInputStream(System.in);
        byte[] buf = new byte[65536];
        try {
            while (true) {
                int n = in.readUnsignedShort();
                in.readFully(buf, 0, n);
                engine.handle(buf, n);
            }
        } catch (EOFException e) {
            System.err.println("stats: in=" + engine.packetsIn + " reflected=" + engine.reflected + " dns=" + engine.dnsAnswered);
        }
    }
}
