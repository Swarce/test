package sgmo.helper;

import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code sign} and {@code verify} commands, built on Google's {@code apksig}.
 *
 * <p>Signing enables APK Signature Schemes v1, v2 and v3 from a PKCS12 keystore. Zip
 * alignment is handled by {@code apksig} itself (see the alignment note in the helper's
 * caveats): with alignment <em>not</em> preserved — the default — it re-aligns every stored
 * entry to 4 bytes and every {@code .so} to the library page size (16384 bytes by default).
 */
final class Sign {
    private Sign() {}

    /** Sign {@code inPath} to {@code outPath}; returns {@code {out, certSha256, schemes}}. */
    static Map<String, Object> sign(String inPath, String outPath, String ksPath,
                                    String ksPassFile, String alias, int minSdk) throws Exception {
        char[] pass = readPassword(ksPassFile);
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (java.io.InputStream in = Files.newInputStream(Paths.get(ksPath))) {
            ks.load(in, pass);
        }
        PrivateKey key = (PrivateKey) ks.getKey(alias, pass);
        if (key == null) {
            throw new IllegalArgumentException("no private key for alias '" + alias + "'");
        }
        Certificate[] chain = ks.getCertificateChain(alias);
        if (chain == null || chain.length == 0) {
            throw new IllegalArgumentException("no certificate chain for alias '" + alias + "'");
        }
        List<X509Certificate> certs = new ArrayList<>();
        for (Certificate c : chain) {
            certs.add((X509Certificate) c);
        }

        ApkSigner.SignerConfig signer =
                new ApkSigner.SignerConfig.Builder("sgm-offline", key, certs).build();

        ApkSigner.Builder builder = new ApkSigner.Builder(Collections.singletonList(signer))
                .setInputApk(new File(inPath))
                .setOutputApk(new File(outPath))
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setV4SigningEnabled(false)
                .setMinSdkVersion(minSdk);
        // mAlignmentPreserved defaults to false, so apksig re-aligns stored entries to 4 bytes
        // and .so entries to setLibraryPageAlignmentBytes (default 16384). Leave the defaults.
        builder.build().sign();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("out", outPath);
        out.put("certSha256", certSha256(certs.get(0)));
        List<String> schemes = new ArrayList<>();
        schemes.add("v1");
        schemes.add("v2");
        schemes.add("v3");
        out.put("schemes", schemes);
        return out;
    }

    /** Verify {@code apkPath}; returns verification flags, cert digests and any errors/warnings. */
    static Map<String, Object> verify(String apkPath, int minSdk) throws Exception {
        ApkVerifier verifier = new ApkVerifier.Builder(new File(apkPath))
                .setMinCheckedPlatformVersion(minSdk)
                .build();
        ApkVerifier.Result result = verifier.verify();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("verified", result.isVerified());
        out.put("v1", result.isVerifiedUsingV1Scheme());
        out.put("v2", result.isVerifiedUsingV2Scheme());
        out.put("v3", result.isVerifiedUsingV3Scheme());

        List<String> certDigests = new ArrayList<>();
        for (X509Certificate c : result.getSignerCertificates()) {
            certDigests.add(certSha256(c));
        }
        out.put("certSha256", certDigests);

        List<String> errors = new ArrayList<>();
        for (ApkVerifier.IssueWithParams e : result.getErrors()) {
            errors.add(e.toString());
        }
        // Scheme-specific signer errors are not in the top-level list; collect them too.
        for (ApkVerifier.Result.V1SchemeSignerInfo s : result.getV1SchemeSigners()) {
            for (ApkVerifier.IssueWithParams e : s.getErrors()) errors.add("v1: " + e);
        }
        for (ApkVerifier.Result.V2SchemeSignerInfo s : result.getV2SchemeSigners()) {
            for (ApkVerifier.IssueWithParams e : s.getErrors()) errors.add("v2: " + e);
        }
        for (ApkVerifier.Result.V3SchemeSignerInfo s : result.getV3SchemeSigners()) {
            for (ApkVerifier.IssueWithParams e : s.getErrors()) errors.add("v3: " + e);
        }
        out.put("errors", errors);

        List<String> warnings = new ArrayList<>();
        for (ApkVerifier.IssueWithParams w : result.getWarnings()) {
            warnings.add(w.toString());
        }
        out.put("warnings", warnings);
        return out;
    }

    static String certSha256(X509Certificate cert) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(cert.getEncoded());
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    private static char[] readPassword(String passFile) throws Exception {
        String s = new String(Files.readAllBytes(Paths.get(passFile)), StandardCharsets.UTF_8);
        // A trailing newline is common in password files; drop one.
        if (s.endsWith("\n")) s = s.substring(0, s.length() - 1);
        if (s.endsWith("\r")) s = s.substring(0, s.length() - 1);
        return s.toCharArray();
    }
}
