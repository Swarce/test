package sgm.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;

/** Minimal HTTP/1.1 server (keep-alive, 100-continue, chunked uploads, optional TLS). */
public final class HttpServer {
    public interface Handler {
        Response handle(Request req) throws Exception;
    }

    public static final class Request {
        public String method;
        public String target;
        public final Map<String, String> headers = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        public byte[] body = new byte[0];
        public InetAddress remote;
        public boolean secure;

        public String path() {
            int q = target.indexOf('?');
            return q < 0 ? target : target.substring(0, q);
        }

        public String query() {
            int q = target.indexOf('?');
            return q < 0 ? "" : target.substring(q + 1);
        }

        /** Host header without the port. */
        public String host() {
            String h = headers.get("Host");
            if (h == null) {
                return "";
            }
            int c = h.lastIndexOf(':');
            return (c > 0 && h.indexOf(']') < c ? h.substring(0, c) : h).toLowerCase(Locale.ROOT);
        }
    }

    /** Produces a response body; throwing aborts the connection (the client sees a failed transfer). */
    public interface BodyWriter {
        void write(OutputStream out) throws IOException;
    }

    public static final class Response {
        public int status;
        public final Map<String, String> headers = new LinkedHashMap<String, String>();
        public byte[] body;
        /** Alternative to body for large payloads (sent from fileOffset to the end). */
        public File file;
        public long fileOffset;
        /** Alternative to body for streamed payloads; length -1 sends it chunked. Not called for HEAD. */
        public BodyWriter writer;
        public long writerLength = -1;

        public Response(int status, byte[] body) {
            this.status = status;
            this.body = body;
        }

        public static Response text(int status, String contentType, String text) {
            Response r = new Response(status, text.getBytes(Protocol.UTF8));
            r.headers.put("Content-Type", contentType);
            return r;
        }

        public static Response json(int status, Object value) {
            return text(status, "application/json; charset=utf-8", Json.write(value));
        }
    }

    private final Handler handler;
    private final SSLContext tls;
    private volatile ServerSocket socket;
    private volatile boolean running;
    public final String name;
    /** Counts TLS handshakes the peer aborted — visible in the log, which usually means cert rejection. */
    public final java.util.concurrent.atomic.AtomicLong tlsFailures = new java.util.concurrent.atomic.AtomicLong();
    /** Counts connections that completed at least one request, so "reached but TLS-failed" is distinguishable. */
    public final java.util.concurrent.atomic.AtomicLong served = new java.util.concurrent.atomic.AtomicLong();

    public HttpServer(String name, Handler handler, SSLContext tls) {
        this.name = name;
        this.handler = handler;
        this.tls = tls;
    }

    /** Binds and starts the accept loop on a daemon thread; returns the bound port. */
    public synchronized int start(String host, int port) throws IOException {
        ServerSocket ss;
        if (tls != null) {
            SSLServerSocket s = (SSLServerSocket) tls.getServerSocketFactory().createServerSocket();
            s.setNeedClientAuth(false);
            ss = s;
        } else {
            ss = new ServerSocket();
        }
        ss.setReuseAddress(true);
        ss.bind(host == null ? new InetSocketAddress(port) : new InetSocketAddress(host, port), 64);
        socket = ss;
        running = true;
        final ServerSocket bound = ss;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop(bound);
            }
        }, name + "-accept");
        t.setDaemon(true);
        t.start();
        Log.i(name + " listening on " + (tls != null ? "https" : "http") + "://" + (host == null ? "*" : host) + ":" + ss.getLocalPort());
        return ss.getLocalPort();
    }

    public synchronized void stop() {
        running = false;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing anyway
            }
            socket = null;
        }
    }

    public boolean isRunning() {
        return running;
    }

    private void acceptLoop(ServerSocket ss) {
        while (running) {
            final Socket s;
            try {
                s = ss.accept();
            } catch (IOException e) {
                if (running) {
                    Log.w(name + " accept failed: " + e);
                    continue;
                }
                return;
            }
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    serve(s);
                }
            }, name + "-conn");
            t.setDaemon(true);
            t.start();
        }
    }

    private void serve(Socket s) {
        try {
            s.setSoTimeout(120000);
            s.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = new BufferedOutputStream(s.getOutputStream());
            while (running) {
                Request req = readRequest(in, out);
                if (req == null) {
                    break;
                }
                req.remote = s.getInetAddress();
                req.secure = tls != null;
                Response resp;
                long t0 = System.currentTimeMillis();
                try {
                    resp = handler.handle(req);
                } catch (Exception e) {
                    Log.e(name + " handler failed for " + req.method + " " + req.target, e);
                    resp = Response.json(500, Json.obj("errorCode", "InternalError", "message", "internal server error"));
                }
                if (resp == null) {
                    resp = new Response(404, new byte[0]);
                }
                served.incrementAndGet();
                boolean close = "close".equalsIgnoreCase(req.headers.get("Connection"));
                writeResponse(out, req, resp, close);
                Log.d(name + " " + req.method + " " + req.target + " -> " + resp.status + " (" + (System.currentTimeMillis() - t0) + " ms)");
                if (close) {
                    break;
                }
            }
        } catch (SocketTimeoutException ignored) {
            // idle keep-alive connection
        } catch (SocketException ignored) {
            // client went away
        } catch (javax.net.ssl.SSLException e) {
            // the game's TLS layer rejected our certificate (or aborted the handshake): the most
            // common reason a redirected game cannot connect, so make it visible in the log
            long n = tlsFailures.incrementAndGet();
            if (n <= 20 || n % 50 == 0) {
                Log.w(name + ": a TLS handshake from " + s.getInetAddress() + " failed (" + e.getMessage()
                        + "). The app may not accept this server's certificate. [" + n + " so far]");
            }
        } catch (IOException e) {
            Log.d(name + " connection error: " + e);
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                int n = sb.length();
                if (n > 0 && sb.charAt(n - 1) == '\r') {
                    sb.setLength(n - 1);
                }
                return sb.toString();
            }
            sb.append((char) c);
            if (sb.length() > 65536) {
                throw new IOException("line too long");
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static Request readRequest(InputStream in, OutputStream out) throws IOException {
        String line = readLine(in);
        while (line != null && line.isEmpty()) {
            line = readLine(in);
        }
        if (line == null) {
            return null;
        }
        String[] parts = line.split(" ");
        if (parts.length < 2) {
            throw new IOException("bad request line");
        }
        Request req = new Request();
        req.method = parts[0].toUpperCase(Locale.ROOT);
        req.target = parts[1];
        if (req.target.startsWith("http://") || req.target.startsWith("https://")) {
            int slash = req.target.indexOf('/', req.target.indexOf("//") + 2);
            req.target = slash < 0 ? "/" : req.target.substring(slash);
        }
        while (true) {
            String h = readLine(in);
            if (h == null || h.isEmpty()) {
                break;
            }
            int c = h.indexOf(':');
            if (c > 0) {
                req.headers.put(h.substring(0, c).trim(), h.substring(c + 1).trim());
            }
        }
        if ("100-continue".equalsIgnoreCase(req.headers.get("Expect"))) {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(Protocol.UTF8));
            out.flush();
        }
        String te = req.headers.get("Transfer-Encoding");
        if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            while (true) {
                String sz = readLine(in);
                if (sz == null) {
                    throw new IOException("truncated chunk");
                }
                int semi = sz.indexOf(';');
                int n = Integer.parseInt((semi < 0 ? sz : sz.substring(0, semi)).trim(), 16);
                if (n == 0) {
                    while (true) {
                        String trailer = readLine(in);
                        if (trailer == null || trailer.isEmpty()) {
                            break;
                        }
                    }
                    break;
                }
                body.write(readFully(in, n));
                readLine(in);
            }
            req.body = body.toByteArray();
        } else {
            String cl = req.headers.get("Content-Length");
            int n = cl == null ? 0 : Integer.parseInt(cl.trim());
            if (n < 0 || n > 64 * 1024 * 1024) {
                throw new IOException("bad content length");
            }
            req.body = readFully(in, n);
        }
        return req;
    }

    private static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) {
                throw new IOException("truncated body");
            }
            off += r;
        }
        return b;
    }

    private static final ThreadLocal<SimpleDateFormat> HTTP_DATE = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("GMT"));
            return f;
        }
    };

    public static String httpDate(long ms) {
        return HTTP_DATE.get().format(new Date(ms));
    }

    private static void writeResponse(OutputStream out, Request req, Response r, boolean close) throws IOException {
        long length = r.writer != null ? r.writerLength
                : r.file != null ? r.file.length() - r.fileOffset : (r.body == null ? 0 : r.body.length);
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(r.status).append(' ').append(reason(r.status)).append("\r\n");
        // the client syncs its clock from the Date header (HttpManager / ServerTime)
        sb.append("Server: sgm-private-server\r\n");
        sb.append("Date: ").append(httpDate(System.currentTimeMillis())).append("\r\n");
        for (Map.Entry<String, String> e : r.headers.entrySet()) {
            sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        boolean chunked = length < 0;
        if (chunked) {
            sb.append("Transfer-Encoding: chunked\r\n");
        } else {
            sb.append("Content-Length: ").append(length).append("\r\n");
        }
        sb.append(close ? "Connection: close\r\n" : "Connection: keep-alive\r\n");
        sb.append("\r\n");
        out.write(sb.toString().getBytes(Protocol.UTF8));
        if (!"HEAD".equals(req.method)) {
            if (r.writer != null) {
                if (chunked) {
                    ChunkedOutput co = new ChunkedOutput(out);
                    r.writer.write(co);
                    co.finish();
                } else {
                    r.writer.write(out);
                }
            } else if (r.file != null) {
                InputStream fin = new FileInputStream(r.file);
                try {
                    long skip = r.fileOffset;
                    while (skip > 0) {
                        long k = fin.skip(skip);
                        if (k <= 0) {
                            throw new IOException("cannot seek in " + r.file);
                        }
                        skip -= k;
                    }
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = fin.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                } finally {
                    fin.close();
                }
            } else if (r.body != null) {
                out.write(r.body);
            }
        }
        out.flush();
    }

    /** HTTP/1.1 chunked transfer coding for bodies of unknown length. */
    private static final class ChunkedOutput extends OutputStream {
        private final OutputStream out;

        ChunkedOutput(OutputStream out) {
            this.out = out;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (len > 0) {
                out.write((Integer.toHexString(len) + "\r\n").getBytes(Protocol.UTF8));
                out.write(b, off, len);
                out.write(CRLF);
            }
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        void finish() throws IOException {
            out.write("0\r\n\r\n".getBytes(Protocol.UTF8));
        }
    }

    private static final byte[] CRLF = {'\r', '\n'};

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 204: return "No Content";
            case 206: return "Partial Content";
            case 302: return "Found";
            case 304: return "Not Modified";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 500: return "Internal Server Error";
            case 502: return "Bad Gateway";
            case 503: return "Service Unavailable";
            default: return "Status";
        }
    }
}
