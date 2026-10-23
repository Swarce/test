package sgm.core;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collections;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

/**
 * Proves the TLS trust path: a client whose trust store holds only the manager's CA completes a
 * real HTTPS request against the server (with hostname verification on), which is exactly what
 * Android's X509TrustManager does once the CA is installed as a system root.
 *
 * usage: java sgm.core.CaTlsTest &lt;content-dir&gt;
 */
public final class CaTlsTest {
    public static void main(String[] args) throws Exception {
        File tmp = File.createTempFile("sgm-catls", "");
        tmp.delete();
        File data = new File(tmp, "data");
        Server server = Server.open(new File(args[0]), data, null);
        SSLContext serverCtx = Tls.serverContext(data, Protocol.API_HOST, Protocol.CDN_HOST);
        HttpServer http = new HttpServer("api", server, serverCtx);
        int port = http.start("127.0.0.1", 0);

        // client trusts ONLY the CA certificate, as a device does after installing it
        X509Certificate ca = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(Tls.caCertPem(data).getBytes(Protocol.UTF8)));
        KeyStore ts = KeyStore.getInstance(KeyStore.getDefaultType());
        ts.load(null, null);
        ts.setCertificateEntry("ca", ca);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);
        SSLContext client = SSLContext.getInstance("TLS");
        client.init(null, tmf.getTrustManagers(), null);

        SSLSocket cs = (SSLSocket) client.getSocketFactory().createSocket();
        SSLParameters p = cs.getSSLParameters();
        p.setServerNames(Collections.<SNIServerName>singletonList(new SNIHostName(Protocol.API_HOST)));
        p.setEndpointIdentificationAlgorithm("HTTPS"); // enforce the SAN/hostname match too
        cs.setSSLParameters(p);
        cs.connect(new InetSocketAddress("127.0.0.1", port), 5000);
        cs.startHandshake();
        OutputStream o = cs.getOutputStream();
        o.write(("GET /v1/ping HTTP/1.1\r\nHost: " + Protocol.API_HOST + "\r\nConnection: close\r\n\r\n").getBytes(Protocol.UTF8));
        o.flush();
        BufferedReader r = new BufferedReader(new InputStreamReader(cs.getInputStream(), Protocol.UTF8));
        String statusLine = r.readLine();
        cs.close();
        http.stop();
        boolean ok = statusLine != null && statusLine.contains(" 200 ");
        System.out.println("CA-only trust store, HTTPS hostname check on -> " + statusLine);
        System.out.println("Android system-CA filename: " + Tls.caAndroidHash(data));
        System.out.println("RESULT " + (ok ? "PASS" : "FAIL"));
        System.exit(ok ? 0 : 1);
    }
}
