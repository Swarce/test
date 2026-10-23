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
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
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
 * The manager's own certificate authority, generated on first use and kept in the data directory.
 *
 * <p>This game validates TLS through Android's {@code X509TrustManager}, which trusts certificates
 * that chain to an installed CA. So the manager runs a small CA: it signs the leaf certificate the
 * server presents (for the API and CDN hostnames), and the CA certificate ({@link #caCertPem}) can
 * be installed on the device as a trusted root. The game then accepts the server with no change to
 * the game itself.
 */
public final class Tls {
    private Tls() {
    }

    public static final String CA_SUBJECT = "SGM Manager Local CA";

    public static SSLContext serverContext(File dir, String... hosts) throws Exception {
        KeyPair ca = ensureCa(dir);
        X509Certificate caCert = parse(readAll(new File(dir, "ca-cert.der")));
        File leafKeyFile = new File(dir, "tls-key.pk8");
        File leafCertFile = new File(dir, "tls-cert.der");
        PrivateKey leafKey;
        X509Certificate leafCert;
        if (leafKeyFile.isFile() && leafCertFile.isFile()) {
            leafKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(readAll(leafKeyFile)));
            leafCert = parse(readAll(leafCertFile));
        } else {
            KeyPair kp = rsa();
            leafKey = kp.getPrivate();
            leafCert = parse(signLeaf(ca, caCert, kp.getPublic(), hosts));
            write(leafKeyFile, leafKey.getEncoded());
            write(leafCertFile, leafCert.getEncoded());
            Log.i("generated a server certificate for " + java.util.Arrays.toString(hosts) + " signed by the manager's CA");
        }
        char[] pass = "sgm".toCharArray();
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        // present the leaf and the CA, so a client that trusts the CA can build the chain
        ks.setKeyEntry("sgm", leafKey, pass, new Certificate[]{leafCert, caCert});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pass);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, new SecureRandom());
        return ctx;
    }

    /** The CA certificate, in PEM, for installing as a trusted root (generated if needed). */
    public static synchronized String caCertPem(File dir) throws Exception {
        ensureCa(dir);
        byte[] der = readAll(new File(dir, "ca-cert.der"));
        StringBuilder sb = new StringBuilder("-----BEGIN CERTIFICATE-----\n");
        String b64 = Protocol.base64(der);
        for (int i = 0; i < b64.length(); i += 64) {
            sb.append(b64, i, Math.min(i + 64, b64.length())).append('\n');
        }
        sb.append("-----END CERTIFICATE-----\n");
        return sb.toString();
    }

    /** Android's system-CA filename for this certificate (subject hash + ".0"). */
    public static String caAndroidHash(File dir) throws Exception {
        ensureCa(dir);
        X509Certificate c = parse(readAll(new File(dir, "ca-cert.der")));
        return String.format("%08x.0", subjectNameHashOld(c.getSubjectX500Principal().getEncoded()));
    }

    private static synchronized KeyPair ensureCa(File dir) throws Exception {
        File caKeyFile = new File(dir, "ca-key.pk8");
        File caCertFile = new File(dir, "ca-cert.der");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        if (caKeyFile.isFile() && caCertFile.isFile()) {
            PrivateKey priv = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(readAll(caKeyFile)));
            X509Certificate cert = parse(readAll(caCertFile));
            return new KeyPair(cert.getPublicKey(), priv);
        }
        KeyPair kp = rsa();
        byte[] cert = selfSignedCa(kp);
        write(caKeyFile, kp.getPrivate().getEncoded());
        write(caCertFile, cert);
        // a new CA invalidates any previously signed leaf
        new File(dir, "tls-key.pk8").delete();
        new File(dir, "tls-cert.der").delete();
        Log.i("generated the manager's local certificate authority (" + CA_SUBJECT + ")");
        return kp;
    }

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048, new SecureRandom());
        return g.generateKeyPair();
    }

    private static X509Certificate parse(byte[] der) throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(der));
    }

    // ---- minimal DER encoding of X.509 v3 certificates ----

    private static final byte[] SIG_ALG = seq(oid(1, 2, 840, 113549, 1, 1, 11), new byte[]{0x05, 0x00}); // sha256WithRSA

    static byte[] selfSignedCa(KeyPair kp) throws Exception {
        byte[] name = name(CA_SUBJECT);
        byte[] ski = ski(kp.getPublic());
        byte[] extensions = tlv(0xa3, seq(
                ext("2.5.29.19", true, seq(bool(true))),                 // basicConstraints: CA:TRUE (critical)
                ext("2.5.29.15", true, bitString(new byte[]{0x06}, 1)),  // keyUsage: keyCertSign, cRLSign (critical)
                ext("2.5.29.14", false, tlv(0x04, ski))));               // subjectKeyIdentifier
        return assemble(kp.getPrivate(), name, name, kp.getPublic(), extensions, 3650);
    }

    static byte[] signLeaf(KeyPair ca, X509Certificate caCert, PublicKey leafPub, String[] hosts) throws Exception {
        ByteArrayOutputStream san = new ByteArrayOutputStream();
        for (String h : hosts) {
            san.write(tlv(0x82, h.getBytes(Protocol.UTF8))); // [2] dNSName
        }
        byte[] aki = seq(tlv(0x80, ski(ca.getPublic()))); // AuthorityKeyIdentifier keyIdentifier [0]
        byte[] extensions = tlv(0xa3, seq(
                ext("2.5.29.19", true, seq()),                                       // basicConstraints: not a CA
                ext("2.5.29.15", true, bitString(new byte[]{(byte) 0xa0}, 5)),        // keyUsage: digitalSignature, keyEncipherment
                ext("2.5.29.37", false, seq(oid(1, 3, 6, 1, 5, 5, 7, 3, 1))),         // extKeyUsage: serverAuth
                ext("2.5.29.17", false, seq(san.toByteArray())),                      // subjectAltName
                ext("2.5.29.35", false, aki),                                         // authorityKeyIdentifier
                ext("2.5.29.14", false, tlv(0x04, ski(leafPub)))));                   // subjectKeyIdentifier
        byte[] issuer = caCert.getSubjectX500Principal().getEncoded();
        return assemble(ca.getPrivate(), issuer, name(hosts[0]), leafPub, extensions, 3650);
    }

    private static byte[] assemble(PrivateKey signWith, byte[] issuer, byte[] subject, PublicKey pub,
                                   byte[] extensions, int days) throws Exception {
        long now = System.currentTimeMillis();
        byte[] validity = seq(utcTime(now - 86400000L), utcTime(Math.min(now + days * 86400000L, 2493072000000L)));
        byte[] serial = tlv(0x02, new BigInteger(63, new SecureRandom()).add(BigInteger.ONE).toByteArray());
        byte[] tbs = seq(tlv(0xa0, tlv(0x02, new byte[]{2})), serial, SIG_ALG, issuer, validity, subject,
                pub.getEncoded(), extensions);
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(signWith);
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] bits = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bits, 1, sig.length);
        return seq(tbs, SIG_ALG, tlv(0x03, bits));
    }

    private static byte[] name(String cn) {
        return seq(set(seq(oid(2, 5, 4, 3), tlv(0x0c, cn.getBytes(Protocol.UTF8)))));
    }

    private static byte[] ext(String oidDotted, boolean critical, byte[] value) {
        String[] p = oidDotted.split("\\.");
        int[] arcs = new int[p.length];
        for (int i = 0; i < p.length; i++) {
            arcs[i] = Integer.parseInt(p[i]);
        }
        if (critical) {
            return seq(oid(arcs), bool(true), tlv(0x04, value));
        }
        return seq(oid(arcs), tlv(0x04, value));
    }

    private static byte[] bool(boolean v) {
        return new byte[]{0x01, 0x01, (byte) (v ? 0xff : 0x00)};
    }

    private static byte[] bitString(byte[] bytes, int unusedBits) {
        byte[] v = new byte[bytes.length + 1];
        v[0] = (byte) unusedBits;
        System.arraycopy(bytes, 0, v, 1, bytes.length);
        return tlv(0x03, v);
    }

    /** 160-bit SHA-1 of the subjectPublicKey BIT STRING contents (RFC 5280 method 1). */
    private static byte[] ski(PublicKey pub) throws Exception {
        byte[] spki = pub.getEncoded();
        // find the public-key BIT STRING: last top-level element of the SPKI SEQUENCE
        int[] pos = {0};
        readTag(spki, pos); // SEQUENCE
        readLen(spki, pos);
        readTag(spki, pos); // AlgorithmIdentifier SEQUENCE
        int algLen = readLen(spki, pos);
        pos[0] += algLen;
        readTag(spki, pos); // BIT STRING
        int bitLen = readLen(spki, pos);
        byte[] key = new byte[bitLen - 1];
        System.arraycopy(spki, pos[0] + 1, key, 0, bitLen - 1); // skip the unused-bits byte
        return MessageDigest.getInstance("SHA-1").digest(key);
    }

    /** OpenSSL's X509_NAME_hash_old (MD5 of the DER-encoded name, first 4 bytes, little-endian). */
    private static long subjectNameHashOld(byte[] nameDer) throws Exception {
        byte[] md = MessageDigest.getInstance("MD5").digest(nameDer);
        return ((md[0] & 0xffL)) | ((md[1] & 0xffL) << 8) | ((md[2] & 0xffL) << 16) | ((md[3] & 0xffL) << 24);
    }

    private static int readTag(byte[] b, int[] pos) {
        return b[pos[0]++] & 0xff;
    }

    private static int readLen(byte[] b, int[] pos) {
        int first = b[pos[0]++] & 0xff;
        if (first < 0x80) {
            return first;
        }
        int n = first & 0x7f;
        int len = 0;
        for (int i = 0; i < n; i++) {
            len = (len << 8) | (b[pos[0]++] & 0xff);
        }
        return len;
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
