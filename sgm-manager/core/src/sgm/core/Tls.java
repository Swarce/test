package sgm.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * Self-signed server certificate, generated on first use and kept in the data directory. The game
 * accepts untrusted chains (HttpsHelper.MyRemoteCertificateValidationCallback), so the
 * certificate never needs to be installed on the phone.
 */
public final class Tls {
    private Tls() {
    }

    public static SSLContext serverContext(File dir, String... hosts) throws Exception {
        File keyFile = new File(dir, "tls-key.pk8");
        File certFile = new File(dir, "tls-cert.der");
        PrivateKey key;
        X509Certificate cert;
        if (keyFile.isFile() && certFile.isFile()) {
            key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(readAll(keyFile)));
            cert = parse(readAll(certFile));
        } else {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048, new SecureRandom());
            KeyPair kp = g.generateKeyPair();
            key = kp.getPrivate();
            cert = parse(selfSigned(kp, hosts));
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("cannot create " + dir);
            }
            write(keyFile, key.getEncoded());
            write(certFile, cert.getEncoded());
            Log.i("generated TLS certificate for " + java.util.Arrays.toString(hosts));
        }
        char[] pass = "sgm".toCharArray();
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setKeyEntry("sgm", key, pass, new Certificate[]{cert});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pass);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, new SecureRandom());
        return ctx;
    }

    private static X509Certificate parse(byte[] der) throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(der));
    }

    // ---- minimal DER encoding of an X.509 v3 certificate ----

    static byte[] selfSigned(KeyPair kp, String[] hosts) throws Exception {
        byte[] sigAlg = seq(oid(1, 2, 840, 113549, 1, 1, 11), new byte[]{0x05, 0x00}); // sha256WithRSAEncryption, NULL
        byte[] name = seq(set(seq(oid(2, 5, 4, 3), tlv(0x0c, hosts[0].getBytes(Protocol.UTF8)))));
        long now = System.currentTimeMillis();
        byte[] validity = seq(utcTime(now - 86400000L), utcTime(Math.min(now + 3650L * 86400000L, 2493072000000L)));
        ByteArrayOutputStream san = new ByteArrayOutputStream();
        for (String h : hosts) {
            san.write(tlv(0x82, h.getBytes(Protocol.UTF8))); // [2] dNSName
        }
        byte[] extensions = tlv(0xa3, seq(
                seq(oid(2, 5, 29, 17), tlv(0x04, seq(san.toByteArray()))),
                seq(oid(2, 5, 29, 19), tlv(0x04, seq()))));  // basicConstraints: not a CA
        byte[] serial = tlv(0x02, new BigInteger(63, new SecureRandom()).add(BigInteger.ONE).toByteArray());
        byte[] tbs = seq(tlv(0xa0, tlv(0x02, new byte[]{2})), serial, sigAlg, name, validity, name,
                kp.getPublic().getEncoded(), extensions);
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(kp.getPrivate());
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] bits = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bits, 1, sig.length);
        return seq(tbs, sigAlg, tlv(0x03, bits));
    }

    private static byte[] utcTime(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x17, f.format(new Date(ms)).getBytes(Protocol.UTF8));
    }

    private static byte[] oid(int... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(parts[0] * 40 + parts[1]);
        for (int k = 2; k < parts.length; k++) {
            int v = parts[k];
            byte[] tmp = new byte[5];
            int n = 0;
            do {
                tmp[n++] = (byte) (v & 0x7f);
                v >>>= 7;
            } while (v != 0);
            for (int j = n - 1; j >= 0; j--) {
                out.write(tmp[j] | (j > 0 ? 0x80 : 0));
            }
        }
        return tlv(0x06, out.toByteArray());
    }

    private static byte[] seq(byte[]... items) {
        return tlv(0x30, cat(items));
    }

    private static byte[] set(byte[]... items) {
        return tlv(0x31, cat(items));
    }

    private static byte[] cat(byte[]... items) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] b : items) {
            out.write(b, 0, b.length);
        }
        return out.toByteArray();
    }

    private static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int len = value.length;
        if (len < 0x80) {
            out.write(len);
        } else if (len < 0x100) {
            out.write(0x81);
            out.write(len);
        } else if (len < 0x10000) {
            out.write(0x82);
            out.write(len >> 8);
            out.write(len & 0xff);
        } else {
            out.write(0x83);
            out.write(len >> 16);
            out.write((len >> 8) & 0xff);
            out.write(len & 0xff);
        }
        out.write(value, 0, value.length);
        return out.toByteArray();
    }

    static byte[] readAll(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void write(File f, byte[] data) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }
}
