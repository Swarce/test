package sgm.core.vpn;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.SSLContext;

import sgm.core.HttpServer;
import sgm.core.Log;
import sgm.core.Protocol;
import sgm.core.Server;
import sgm.core.Tls;

/**
 * Listeners behind the VPN's virtual addresses. Addresses live in 10.111.222.0/24:
 * .1 the interface (game side), .2 the DNS resolver, .3 the game API, .4 the CDN stand-in and
 * the connectivity-check responder.
 */
public final class Gateway {
    public static final String NET = "10.111.222.";
    public static final String TUN_ADDR = NET + "1";
    public static final String DNS_ADDR = NET + "2";
    public static final String API_ADDR = NET + "3";
    public static final String CDN_ADDR = NET + "4";
    /** Hosts the game uses to test for internet access (InternetReachabilityVerifier). */
    public static final String[] CONNECTIVITY_HOSTS = {"clients3.google.com", "connectivitycheck.gstatic.com"};

    private final Server server;
    private final File dataDir;
    private final boolean storeAll;
    private final CdnCache.Opener opener;
    private final List<HttpServer> listeners = new ArrayList<HttpServer>();
    public CdnCache cdn;
    private int apiPort;
    private int cdnPort;
    private int httpPort;

    /** storeAll: keep a copy of every downloaded asset bundle (index files are always kept). */
    public Gateway(Server server, File dataDir, boolean storeAll, CdnCache.Opener opener) {
        this.server = server;
        this.dataDir = dataDir;
        this.storeAll = storeAll;
        this.opener = opener;
    }

    /** Starts the listeners on bindAddr (the VPN interface address) with free ports. */
    public void start(String bindAddr) throws Exception {
        SSLContext tls = Tls.serverContext(dataDir, Protocol.API_HOST, Protocol.CDN_HOST);
        HttpServer api = new HttpServer("api", server, tls);
        apiPort = api.start(bindAddr, 0);
        listeners.add(api);
        cdn = new CdnCache(new File(dataDir, "cdn-cache"), Protocol.CDN_HOST, opener, storeAll);
        final CdnCache cache = cdn;
        final CdnCache.Captive captive = new CdnCache.Captive();
        final Server api2 = server;
        HttpServer plain = new HttpServer("http", new HttpServer.Handler() {
            @Override
            public HttpServer.Response handle(HttpServer.Request req) throws Exception {
                String host = req.host();
                if (host.equals(Protocol.CDN_HOST)) {
                    return cache.handle(req);
                }
                if (host.equals(Protocol.API_HOST)) {
                    return api2.handle(req);
                }
                return captive.handle(req);
            }
        }, null);
        httpPort = plain.start(bindAddr, 0);
        listeners.add(plain);
        HttpServer c = new HttpServer("cdn", cdn, tls);
        cdnPort = c.start(bindAddr, 0);
        listeners.add(c);
    }

    public PacketEngine engine(PacketEngine.Output out, PacketEngine.DnsUpstream upstream) {
        PacketEngine e = new PacketEngine(out, upstream, PacketEngine.ip(DNS_ADDR));
        int api = PacketEngine.ip(API_ADDR);
        int cdnIp = PacketEngine.ip(CDN_ADDR);
        e.mapHost(Protocol.API_HOST, api);
        e.mapPort(api, 443, apiPort);
        e.mapPort(api, 80, httpPort);
        e.mapHost(Protocol.CDN_HOST, cdnIp);
        for (String h : CONNECTIVITY_HOSTS) {
            e.mapHost(h, cdnIp);
        }
        e.mapPort(cdnIp, 443, cdnPort);
        e.mapPort(cdnIp, 80, httpPort);
        Log.i("gateway: api -> " + API_ADDR + " (port " + apiPort + "), cdn + connectivity check -> " + CDN_ADDR
                + " (ports " + cdnPort + "/" + httpPort + ")" + (storeAll ? ", keeping copies of all downloads" : ""));
        return e;
    }

    /** Virtual addresses that must be routed into the VPN interface. */
    public List<String> routes() {
        List<String> r = new ArrayList<String>();
        r.add(DNS_ADDR);
        r.add(API_ADDR);
        r.add(CDN_ADDR);
        return r;
    }

    public void stop() {
        for (HttpServer h : listeners) {
            h.stop();
        }
        listeners.clear();
    }

    /** Total TLS handshakes the game aborted across the HTTPS listeners (usually = cert rejection). */
    public long tlsFailures() {
        long n = 0;
        for (HttpServer h : listeners) {
            n += h.tlsFailures.get();
        }
        return n;
    }

    /** Requests that completed (got past TLS), across all listeners. */
    public long served() {
        long n = 0;
        for (HttpServer h : listeners) {
            n += h.served.get();
        }
        return n;
    }
}
