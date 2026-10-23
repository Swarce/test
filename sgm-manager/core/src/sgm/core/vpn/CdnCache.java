package sgm.core.vpn;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.Random;

import sgm.core.HttpServer;
import sgm.core.Log;

/**
 * Stands in for the game's asset CDN (skgm-prod-cdn): files are streamed from the real CDN while a
 * copy is kept on the phone, and the copies are served whenever the CDN cannot be reached, which
 * is what lets the game start without internet once its files went through here.
 *
 * <p>The start screen first downloads maintenanceMode.json; a connection failure there is the
 * game's error 867, while any HTTP error means "no maintenance". It is always answered locally,
 * so neither a missing CDN nor an official maintenance notice blocks the private server.
 */
public final class CdnCache implements HttpServer.Handler {
    /** Opens a connection outside the VPN (Network.openConnection on Android). */
    public interface Opener {
        HttpURLConnection open(URL url) throws IOException;
    }

    /** FileDownloader gives up after 15 s without data; stay well below that. */
    static final int CONNECT_TIMEOUT_MS = 8000;
    static final int READ_TIMEOUT_MS = 12000;

    private final File dir;
    private final String upstreamHost;
    private final Opener opener;
    /** Keep a copy of every file (asset bundles too), not only the small index files. */
    public volatile boolean storeAll;
    private final Random rnd = new Random();
    public volatile long hits;
    public volatile long fetched;
    public volatile long misses;
    public volatile long maintenanceAnswered;
    /** Outcome of the last request that went to the real CDN, for the status screen. */
    public volatile String lastUpstream = "not contacted yet";

    public CdnCache(File dir, String upstreamHost, Opener opener, boolean storeAll) {
        this.dir = dir;
        this.upstreamHost = upstreamHost;
        this.opener = opener;
        this.storeAll = storeAll;
    }

    /** Small index files that change with every content update: refreshed whenever possible. */
    static boolean indexFile(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        return p.endsWith("fingerprints.bytes") || p.endsWith("vram.bytes") || p.endsWith(".json") || p.endsWith(".txt");
    }

    File fileFor(String path) {
        StringBuilder sb = new StringBuilder();
        for (String part : path.replace('\\', '/').split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(part.replaceAll("[^A-Za-z0-9._-]", "_"));
        }
        return new File(dir, sb.length() == 0 ? "_root" : sb.toString());
    }

    /** "bytes=N-" (how FileDownloader resumes); 0 when absent or not of that form. */
    static long rangeStart(HttpServer.Request req) {
        String range = req.headers.get("Range");
        if (range == null || !range.startsWith("bytes=") || !range.endsWith("-")) {
            return 0;
        }
        try {
            return Math.max(0, Long.parseLong(range.substring(6, range.length() - 1).trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public HttpServer.Response handle(HttpServer.Request req) throws Exception {
        String path = req.path();
        if (path.toLowerCase(Locale.ROOT).endsWith("maintenancemode.json")) {
            if (maintenanceAnswered++ == 0) {
                Log.i("cdn: answered the maintenance check locally (no maintenance)");
            }
            return new HttpServer.Response(404, new byte[0]);
        }
        if (!req.method.equals("GET") && !req.method.equals("HEAD")) {
            return new HttpServer.Response(405, new byte[0]);
        }
        File f = fileFor(path);
        long from = rangeStart(req);
        boolean have = f.isFile();
        if (have && !indexFile(path)) {
            hits++;
            return serveFile(f, from);
        }
        HttpURLConnection c;
        int code;
        try {
            if (opener == null) {
                throw new IOException("no internet connection");
            }
            URL url = new URL("https://" + upstreamHost + path + (req.query().isEmpty() ? "" : "?" + req.query()));
            c = opener.open(url);
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestMethod(req.method);
            c.setRequestProperty("Accept-Encoding", "identity");
            code = c.getResponseCode();
        } catch (IOException e) {
            lastUpstream = "unreachable: " + e.getMessage();
            if (have) {
                hits++;
                Log.i("cdn: CDN unreachable, serving the saved copy of " + path);
                return serveFile(f, from);
            }
            misses++;
            Log.w("cdn: " + path + " is not saved and the CDN is unreachable (" + e + ")");
            return new HttpServer.Response(503, new byte[0]);
        }
        lastUpstream = "HTTP " + code;
        if (code != 200) {
            c.disconnect();
            if (have) {
                hits++;
                Log.i("cdn: CDN answered " + code + " for " + path + ", serving the saved copy");
                return serveFile(f, from);
            }
            misses++;
            Log.w("cdn: CDN answered " + code + " for " + path);
            return new HttpServer.Response(code >= 400 ? code : 502, new byte[0]);
        }
        return stream(req, c, f, from, storeAll || indexFile(path), path);
    }

    private static HttpServer.Response serveFile(File f, long from) {
        HttpServer.Response r = new HttpServer.Response(200, null);
        r.file = f;
        r.headers.put("Content-Type", "application/octet-stream");
        r.headers.put("Last-Modified", HttpServer.httpDate(f.lastModified()));
        if (from > 0 && from < f.length()) {
            r.status = 206;
            r.fileOffset = from;
            r.headers.put("Content-Range", "bytes " + from + "-" + (f.length() - 1) + "/" + f.length());
        }
        return r;
    }

    /** Passes the CDN's response through to the game while (optionally) saving it. */
    private HttpServer.Response stream(HttpServer.Request req, final HttpURLConnection c, final File f, long from,
                                       final boolean store, final String path) throws IOException {
        final long total = c.getContentLength();
        final long skip = total >= 0 && from > 0 && from < total ? from : 0;
        HttpServer.Response r = new HttpServer.Response(skip > 0 ? 206 : 200, null);
        r.headers.put("Content-Type", "application/octet-stream");
        if (skip > 0) {
            r.headers.put("Content-Range", "bytes " + skip + "-" + (total - 1) + "/" + total);
        }
        r.writerLength = total >= 0 ? total - skip : -1;
        if (req.method.equals("HEAD")) {
            c.disconnect();
            return r;
        }
        r.writer = new HttpServer.BodyWriter() {
            @Override
            public void write(OutputStream client) throws IOException {
                File tmp = null;
                OutputStream copy = null;
                if (store) {
                    File parent = f.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("cannot create " + parent);
                    }
                    tmp = new File(f.getPath() + "." + Integer.toHexString(rnd.nextInt()) + ".part");
                    copy = new FileOutputStream(tmp);
                }
                boolean ok = false;
                long n = 0;
                InputStream in = c.getInputStream();
                try {
                    byte[] buf = new byte[65536];
                    int k;
                    while ((k = in.read(buf)) > 0) {
                        if (copy != null) {
                            copy.write(buf, 0, k);
                        }
                        long end = n + k;
                        if (end > skip) {
                            int start = (int) Math.max(0, skip - n);
                            client.write(buf, start, k - start);
                        }
                        n = end;
                    }
                    if (total >= 0 && n != total) {
                        throw new IOException("CDN transfer of " + path + " cut short (" + n + " of " + total + " bytes)");
                    }
                    ok = true;
                } finally {
                    in.close();
                    c.disconnect();
                    if (copy != null) {
                        copy.close();
                        if (ok && tmp.renameTo(f)) {
                            Log.d("cdn: saved " + path + " (" + n + " bytes)");
                        } else if (!tmp.delete()) {
                            tmp.deleteOnExit();
                        }
                    }
                }
                fetched++;
                if (indexFile(path)) {
                    Log.i("cdn: " + path + " fetched from the CDN (" + n + " bytes)" + (store ? ", copy saved" : ""));
                }
            }
        };
        return r;
    }

    /** Answers the connectivity probe the game uses (clients3.google.com/generate_204). */
    public static final class Captive implements HttpServer.Handler {
        @Override
        public HttpServer.Response handle(HttpServer.Request req) {
            if (req.path().endsWith("generate_204") || req.path().endsWith("gen_204")) {
                return new HttpServer.Response(204, new byte[0]);
            }
            return HttpServer.Response.text(200, "text/html", "<html><body>Success</body></html>");
        }
    }

    /** Total size of the saved files, in bytes. */
    public long size() {
        return size(dir);
    }

    public static long size(File d) {
        long n = 0;
        File[] files = d.listFiles();
        if (files != null) {
            for (File f : files) {
                n += f.isDirectory() ? size(f) : f.length();
            }
        }
        return n;
    }

    /** Whether copies of the start-up index files exist (needed to start without internet). */
    public boolean hasIndexFiles() {
        return fileFor("/v1/fingerprints.bytes").isFile() && fileFor("/v1/vram.bytes").isFile();
    }
}
