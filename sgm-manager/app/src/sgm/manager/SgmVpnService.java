package sgm.manager;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import sgm.core.Log;
import sgm.core.Protocol;
import sgm.core.Server;
import sgm.core.vpn.CdnCache;
import sgm.core.vpn.Gateway;
import sgm.core.vpn.PacketEngine;
import sgm.core.vpn.ResolvingDnsUpstream;
import sgm.core.vpn.UdpDnsUpstream;

/**
 * Local VPN that only carries the game's traffic to a few virtual addresses (DNS resolver, game
 * API, CDN stand-in). Everything else the game does keeps using the normal network.
 */
public final class SgmVpnService extends VpnService {
    static final String ACTION_START = "sgm.manager.START";
    static final String ACTION_STOP = "sgm.manager.STOP";
    /** Keep a copy of every downloaded asset bundle (start-up index files are always kept). */
    static final String EXTRA_KEEP_DOWNLOADS = "keepDownloads";

    static volatile boolean running;
    static volatile String status = "stopped";
    static volatile PacketEngine engine;
    static volatile Gateway gateway;
    static volatile String cdnStatus = "not checked";

    private ParcelFileDescriptor tun;
    private Thread reader;
    private volatile boolean stopping;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    synchronized (SgmVpnService.this) {
                        shutdown();
                    }
                    stopSelf();
                }
            }, "sgm-stop").start();
            return START_NOT_STICKY;
        }
        if (!running) {
            final boolean keepDownloads = intent != null && intent.getBooleanExtra(EXTRA_KEEP_DOWNLOADS, true);
            startForeground(1, notification(this, "Starting…"));
            status = "starting…";
            // loading game data and creating the TLS key can take seconds: keep the main thread free
            new Thread(new Runnable() {
                @Override
                public void run() {
                    synchronized (SgmVpnService.this) {
                        if (running) {
                            return;
                        }
                        try {
                            begin(keepDownloads);
                        } catch (Exception e) {
                            Log.e("could not start", e);
                            status = "error: " + e.getMessage();
                            shutdown();
                            stopSelf();
                        }
                    }
                }
            }, "sgm-start").start();
        }
        return START_STICKY;
    }

    private void begin(boolean keepDownloads) throws Exception {
        Server server = Engine.get(this).server();
        stopping = false;
        Builder b = new Builder();
        b.setSession("SGM Manager");
        b.setMtu(1500);
        b.addAddress(Gateway.TUN_ADDR, 24);
        b.addDnsServer(Gateway.DNS_ADDR);
        Gateway gw = new Gateway(server, Engine.get(this).dataDir, keepDownloads, new CdnCache.Opener() {
            @Override
            public HttpURLConnection open(URL url) throws IOException {
                Network n = underlyingNetwork();
                if (n == null) {
                    throw new IOException("no internet connection");
                }
                return (HttpURLConnection) n.openConnection(url);
            }
        });
        for (String r : gw.routes()) {
            b.addRoute(r, 32);
        }
        try {
            b.addAllowedApplication(Engine.GAME_PACKAGE);
        } catch (Exception e) {
            throw new IOException("Skullgirls (" + Engine.GAME_PACKAGE + ") is not installed");
        }
        // our own replies to the game must flow back through the interface as well
        b.addAllowedApplication(getPackageName());
        b.setConfigureIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
        tun = b.establish();
        if (tun == null) {
            throw new IOException("VPN permission was revoked");
        }
        gw.start(Gateway.TUN_ADDR);
        final FileOutputStream out = new FileOutputStream(tun.getFileDescriptor());
        PacketEngine pe = gw.engine(new PacketEngine.Output() {
            @Override
            public void write(byte[] p, int off, int len) throws IOException {
                synchronized (out) {
                    out.write(p, off, len);
                }
            }
        }, new ResolvingDnsUpstream(new ResolvingDnsUpstream.Resolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                // the real network's resolver: follows the phone's DNS settings, never loops into the VPN
                Network n = underlyingNetwork();
                if (n == null) {
                    throw new UnknownHostException("no internet connection");
                }
                return n.getAllByName(host);
            }
        }, new UdpDnsUpstream(upstreamDns(), new UdpDnsUpstream.Protector() {
            @Override
            public boolean protect(DatagramSocket s) {
                return SgmVpnService.this.protect(s);
            }
        })));
        gateway = gw;
        engine = pe;
        final FileInputStream in = new FileInputStream(tun.getFileDescriptor());
        final PacketEngine fe = pe;
        reader = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[32767];
                try {
                    while (!stopping) {
                        int n = in.read(buf);
                        if (n > 0) {
                            fe.handle(buf, n);
                        }
                    }
                } catch (IOException e) {
                    if (!stopping) {
                        Log.e("VPN read loop stopped", e);
                    }
                }
            }
        }, "sgm-tun");
        reader.start();
        running = true;
        status = "running";
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(1, notification(this, "Private server active"));
        Log.i("VPN up: only " + Engine.GAME_PACKAGE + " is redirected");
        probeCdn(gw);
    }

    /** One look at the official CDN, so the status screen can say whether downloads can work. */
    private void probeCdn(final Gateway gw) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String result;
                try {
                    Network n = underlyingNetwork();
                    if (n == null) {
                        throw new IOException("no internet connection");
                    }
                    HttpURLConnection c = (HttpURLConnection) n.openConnection(new URL("https://" + Protocol.CDN_HOST + "/v1/fingerprints.bytes"));
                    c.setRequestMethod("HEAD");
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    int code = c.getResponseCode();
                    c.disconnect();
                    result = code == 200 ? "reachable" : "answered HTTP " + code;
                } catch (Exception e) {
                    result = "unreachable (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")";
                }
                cdnStatus = result;
                boolean saved = gw.cdn != null && gw.cdn.hasIndexFiles();
                Log.i("official download server (CDN): " + result + "; saved start-up files: " + (saved ? "yes" : "no"));
            }
        }, "sgm-probe").start();
    }

    private void shutdown() {
        stopping = true;
        running = false;
        if (gateway != null) {
            gateway.stop();
            gateway = null;
        }
        if (engine != null) {
            engine.shutdown();
            engine = null;
        }
        if (tun != null) {
            // a blocked read() on the interface does not return on close; poke it with a packet
            // (from a worker thread: this also runs on the main thread, where sockets are not allowed)
            Thread poke = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        DatagramSocket s = new DatagramSocket();
                        byte[] b = new byte[1];
                        s.send(new DatagramPacket(b, 1, InetAddress.getByName(Gateway.DNS_ADDR), 9));
                        s.close();
                    } catch (Exception ignored) {
                        // best effort
                    }
                }
            }, "sgm-poke");
            poke.start();
            try {
                poke.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            try {
                tun.close();
            } catch (IOException ignored) {
                // closing anyway
            }
            tun = null;
        }
        if (!status.startsWith("error")) {
            status = "stopped";
        }
        stopForeground(true);
    }

    @Override
    public void onRevoke() {
        Log.i("VPN revoked by the system or another VPN app");
        synchronized (this) {
            shutdown();
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        synchronized (this) {
            shutdown();
        }
        super.onDestroy();
    }

    /** The real network (Wi-Fi / mobile), for traffic that must not loop back into the VPN. */
    Network underlyingNetwork() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        Network best = null;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc == null || nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    || !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                continue;
            }
            if (best == null || nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                best = n;
            }
        }
        return best;
    }

    private List<InetAddress> upstreamDns() {
        List<InetAddress> out = new ArrayList<InetAddress>();
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        Network n = underlyingNetwork();
        LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
        if (lp != null) {
            out.addAll(lp.getDnsServers());
        }
        try {
            out.add(InetAddress.getByName("1.1.1.1"));
            out.add(InetAddress.getByName("8.8.8.8"));
        } catch (IOException ignored) {
            // literals never fail
        }
        return out;
    }

    static Notification notification(Context c, String text) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = Compat.notificationBuilder(c, "sgm", "Private server");
        } else {
            b = new Notification.Builder(c);
        }
        Intent open = new Intent(c, MainActivity.class);
        b.setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentTitle("SGM Manager")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        return b.build();
    }
}
