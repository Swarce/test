package sgm.core;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Wire-protocol details shared with the game client (see docs/PROTOCOL.md). */
public final class Protocol {
    private Protocol() {
    }

    public static final Charset UTF8 = Charset.forName("UTF-8");
    public static final String API_PREFIX = "/v1/";
    /** ClientAPI.signatureSecret (set in ClientAPI..cctor). */
    public static final String SIGNATURE_SECRET = "k5fh3Xx+Dk{;h*YR]pHRs-$ya*XeaT)Z[ukdf-7Vk]vb53S;L<992+t%)SCpP[C,";
    public static final String SIG_HEADER = "X-SG-SIG";
    public static final String SESSION_COOKIE = "sgsession";
    public static final String API_HOST = "skgm-prod-game-api.skullgirlsmobile.com";
    public static final String CDN_HOST = "skgm-prod-cdn.skullgirlsmobile.com";

    public static String sha256Hex(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02X", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** NetworkRequest.GenerateRequestSignature(endpoint, queries, body). */
    public static String requestSignature(String endpoint, List<String> queries, String body) {
        StringBuilder s = new StringBuilder();
        if (endpoint != null && !endpoint.isEmpty()) {
            s.append(API_PREFIX).append(endpoint);
        }
        if (queries != null) {
            List<String> q = new ArrayList<String>(queries);
            Collections.sort(q, new Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    int c = a.compareToIgnoreCase(b);
                    return c != 0 ? c : a.compareTo(b);
                }
            });
            for (String x : q) {
                s.append(x);
            }
        }
        if (body != null) {
            s.append(body);
        }
        s.append(SIGNATURE_SECRET);
        return sha256Hex(s.toString().getBytes(UTF8));
    }

    /** NetworkRequest.HasValidResponseSignature: no endpoint, no queries, decoded body. */
    public static String responseSignature(String body) {
        return requestSignature(null, null, body);
    }

    // ---- Base64 (java.util.Base64 is unavailable before Android 8) ----

    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    public static String base64(byte[] in) {
        StringBuilder sb = new StringBuilder((in.length + 2) / 3 * 4);
        for (int i = 0; i < in.length; i += 3) {
            int b0 = in[i] & 0xff;
            int b1 = i + 1 < in.length ? in[i + 1] & 0xff : 0;
            int b2 = i + 2 < in.length ? in[i + 2] & 0xff : 0;
            sb.append(B64[b0 >> 2]).append(B64[((b0 & 3) << 4) | (b1 >> 4)]);
            sb.append(i + 1 < in.length ? B64[((b1 & 15) << 2) | (b2 >> 6)] : '=');
            sb.append(i + 2 < in.length ? B64[b2 & 63] : '=');
        }
        return sb.toString();
    }

    public static byte[] unbase64(String s) {
        int[] rev = new int[128];
        for (int i = 0; i < 128; i++) {
            rev[i] = -1;
        }
        for (int i = 0; i < B64.length; i++) {
            rev[B64[i]] = i;
        }
        rev['-'] = 62;
        rev['_'] = 63;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int acc = 0;
        int bits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '=' || c >= 128 || rev[c] < 0) {
                continue;
            }
            acc = (acc << 6) | rev[c];
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((acc >> bits) & 0xff);
            }
        }
        return out.toByteArray();
    }

    /** Raised by handlers; rendered as {"errorCode", "message"} which NetworkRequest.FinishRequest reads. */
    public static final class ApiError extends RuntimeException {
        public final int status;
        public final String code;

        public ApiError(int status, String code, String message) {
            super(message == null || message.isEmpty() ? code : message);
            this.status = status;
            this.code = code;
        }
    }
}
