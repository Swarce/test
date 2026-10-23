package sgm.core.vpn;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

import sgm.core.HttpServer;
import sgm.core.Log;

/**
 * Caching proxy for the game's asset CDN. While online every downloaded file is stored on the
 * phone; without internet the stored copies are served, which is what makes offline play possible
 * once the assets have been downloaded through it at least once.
 */
public final class CdnCache implements HttpServer.Handler {
    /** Opens a connection outside the VPN (Network.openConnection on Android). */
    public interface Opener {
        HttpURLConnection open(URL url) throws IOException;
    }

    private final File dir;
    private final String upstreamHost;
    private final Opener opener;
    public volatile long hits;
    public volatile long fetched;
    public volatile long misses;

    public CdnCache(File dir, String upstreamHost, Opener opener) {
        this.dir = dir;
        this.upstreamHost = upstreamHost;
        this.opener = opener;
    }

    /** Small index files change with every content update: refresh them whenever possible. */
    static boolean volatileFile(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        return p.endsWith("fingerprints.bytes") || p.endsWith("vram.bytes") || p.endsWith(".json") || p.endsWith(".txt");
    }

    File fileFor(String path) {
        String clean = path.replace('\\', '/');
        StringBuilder sb = new StringBuilder();
        for (String part : clean.split("/")) {
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

    @Override
    public HttpServer.Response handle(HttpServer.Request req) throws Exception {
        if (!req.method.equals("GET") && !req.method.equals("HEAD")) {
            return new HttpServer.Response(405, new byte[0]);
        }
        String path = req.path();
        File f = fileFor(path);
        boolean have = f.isFile();
        if (!have || volatileFile(path)) {
            try {
                if (download(path, req.query(), f)) {
                    fetched++;
                    have = true;
                } else if (!have) {
                    misses++;
                    return new HttpServer.Response(404, new byte[0]);
                }
            } catch (IOException e) {
                if (!have) {
                    misses++;
                    Log.i("cdn: not cached and upstream unreachable: " + path + " (" + e.getMessage() + ")");
                    return new HttpServer.Response(502, new byte[0]);
                }
            }
        } else {
            hits++;
        }
        HttpServer.Response r = new HttpServer.Response(200, null);
        r.file = f;
        r.headers.put("Content-Type", "application/octet-stream");
        r.headers.put("Last-Modified", HttpServer.httpDate(f.lastModified()));
        String range = req.headers.get("Range");
        if (range != null && range.startsWith("bytes=") && range.endsWith("-")) {
            // FileDownloader resumes interrupted downloads with "bytes=N-"
            long from = Long.parseLong(range.substring(6, range.length() - 1).trim());
            if (from > 0 && from < f.length()) {
                r.status = 206;
                r.fileOffset = from;
                r.headers.put("Content-Range", "bytes " + from + "-" + (f.length() - 1) + "/" + f.length());
            }
        }
        return r;
    }

    /** Fetches path from the real CDN into f. Returns false on a 404 from upstream. */
    private boolean download(String path, String query, File f) throws IOException {
        if (opener == null) {
            throw new IOException("offline");
        }
        URL url = new URL("https://" + upstreamHost + path + (query.isEmpty() ? "" : "?" + query));
        HttpURLConnection c = opener.open(url);
        c.setConnectTimeout(8000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "sgm-manager");
        int code = c.getResponseCode();
        if (code == 404 || code == 403) {
            c.disconnect();
            return false;
        }
        if (code != 200) {
            c.disconnect();
            throw new IOException("upstream HTTP " + code);
        }
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        File tmp = new File(f.getPath() + ".part");
        InputStream in = c.getInputStream();
        OutputStream out = new FileOutputStream(tmp);
        long n = 0;
        try {
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
                n += r;
            }
        } finally {
            out.close();
            in.close();
            c.disconnect();
        }
        long expected = c.getContentLength();
        if (expected >= 0 && expected != n) {
            tmp.delete();
            throw new IOException("short download");
        }
        if (!tmp.renameTo(f)) {
            tmp.delete();
            throw new IOException("cannot store " + f);
        }
        Log.i("cdn: cached " + path + " (" + n + " bytes)");
        return true;
    }

    /** Answers the connectivity probes the game (and Android) use when there is no internet. */
    public static final class Captive implements HttpServer.Handler {
        @Override
        public HttpServer.Response handle(HttpServer.Request req) {
            if (req.path().endsWith("generate_204") || req.path().endsWith("gen_204")) {
                return new HttpServer.Response(204, new byte[0]);
            }
            return HttpServer.Response.text(200, "text/html", "<html><body>Success</body></html>");
        }
    }

    /** Total size of the cache, in bytes. */
    public long size() {
        return size(dir);
    }

    private static long size(File d) {
        long n = 0;
        File[] files = d.listFiles();
        if (files != null) {
            for (File f : files) {
                n += f.isDirectory() ? size(f) : f.length();
            }
        }
        return n;
    }
}
