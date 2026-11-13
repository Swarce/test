package sgmo.helper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Entry point for the sgmo dex/sign helper.
 *
 * <p>Every subcommand prints JSON to stdout on success, writes a diagnostic to stderr on
 * failure, and exits non-zero on failure. Exit codes: {@code 1} generic error,
 * {@code 2} a required dex-patch site mismatched/was missing, {@code 3} dex-compare found
 * differences.
 *
 * <pre>
 *   dex-methods &lt;in.dex&gt;
 *   dex-patch   &lt;in.dex&gt; &lt;out.dex&gt; &lt;spec.json&gt;
 *   dex-compare &lt;a.dex&gt; &lt;b.dex&gt; &lt;exclude.json&gt;
 *   sign   --in &lt;apk&gt; --out &lt;apk&gt; --ks &lt;p12&gt; --ks-pass-file &lt;file&gt; --alias &lt;a&gt; [--min-sdk 24]
 *   verify &lt;apk&gt; [--min-sdk 24]
 *   make-test-dex &lt;out.dex&gt;
 *   selftest
 * </pre>
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("usage: <command> [...]; commands: dex-methods, dex-patch, "
                    + "dex-compare, sign, verify, make-test-dex, selftest");
            System.exit(1);
        }
        String cmd = args[0];
        try {
            switch (cmd) {
                case "dex-methods":
                    requireArgs(args, 2, "dex-methods <in.dex>");
                    Methods.run(args[1], System.out);
                    break;
                case "dex-patch":
                    runDexPatch(args);
                    break;
                case "dex-compare":
                    runDexCompare(args);
                    break;
                case "sign":
                    runSign(args);
                    break;
                case "verify":
                    runVerify(args);
                    break;
                case "make-test-dex":
                    requireArgs(args, 2, "make-test-dex <out.dex>");
                    Files.write(Paths.get(args[1]), Synth.build());
                    System.out.println(Json.write(mapOf("out", args[1])));
                    break;
                case "make-boot-dex": {
                    // make-boot-dex <out.dex> [fill.json]  (fill.json: [{cls,name}] fields set to "")
                    requireArgs(args, 2, "make-boot-dex <out.dex> [fill.json]");
                    java.util.List<Pairip.Str> fill = new ArrayList<>();
                    if (args.length >= 3) {
                        Object parsedFill = Json.parse(readFile(args[2]));
                        java.util.List<Object> raw;
                        if (parsedFill instanceof Map) {
                            @SuppressWarnings("unchecked")
                            java.util.List<Object> inner = (java.util.List<Object>) ((Map<String, Object>) parsedFill).get("fields");
                            raw = inner;
                        } else {
                            @SuppressWarnings("unchecked")
                            java.util.List<Object> arr = (java.util.List<Object>) parsedFill;
                            raw = arr;
                        }
                        for (Object o : raw) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> m = (Map<String, Object>) o;
                            fill.add(new Pairip.Str(str(m, "cls"), str(m, "name")));
                        }
                    }
                    Files.write(Paths.get(args[1]), Boot.buildDex(fill));
                    Map<String, Object> bootOut = new LinkedHashMap<>();
                    bootOut.put("out", args[1]);
                    bootOut.put("filled", fill.size());
                    System.out.println(Json.write(bootOut));
                    break;
                }
                case "pairip-strings": {
                    // pairip-strings <in.dex> [in2.dex ...]
                    requireArgs(args, 2, "pairip-strings <in.dex> [in2.dex ...]");
                    java.util.List<String> dexes = new ArrayList<>();
                    for (int i = 1; i < args.length; i++) dexes.add(args[i]);
                    System.out.println(Json.write(Pairip.listCommand(dexes)));
                    break;
                }
                case "dex-inject-call":
                    runDexInject(args);
                    break;
                case "dex-edit":
                    runDexEdit(args);
                    break;
                case "selftest":
                    SelfTest.run(System.err);
                    System.out.println(Json.write(mapOf("ok", Boolean.TRUE)));
                    break;
                default:
                    System.err.println("unknown command: " + cmd);
                    System.exit(1);
            }
        } catch (Patch.RequiredSiteFailure e) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("sites", e.siteResults);
            out.put("out", null);
            System.out.println(Json.write(out));
            System.err.println("dex-patch: a required site mismatched or was missing");
            System.exit(2);
        } catch (Throwable t) {
            System.err.println(cmd + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
            System.exit(1);
        }
    }

    private static void runDexEdit(String[] args) throws Exception {
        requireArgs(args, 4, "dex-edit <in.dex> <out.dex> <spec.json>");
        @SuppressWarnings("unchecked")
        Map<String, Object> spec = (Map<String, Object>) Json.parse(readFile(args[3]));
        @SuppressWarnings("unchecked")
        List<Object> raw = (List<Object>) spec.get("ops");
        if (raw == null) throw new IllegalArgumentException("spec has no 'ops' array");
        List<DexEdit.Op> ops = new ArrayList<>();
        for (Object o : raw) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) o;
            DexEdit.Op op = new DexEdit.Op();
            op.kind = str(m, "kind");
            op.cls = str(m, "cls");
            op.name = str(m, "name");
            op.desc = str(m, "desc");
            op.expectRawSha256 = str(m, "expectRawSha256");
            String stub = str(m, "stub");
            if (stub != null) op.stub = Patch.Stub.valueOf(stub);
            op.superCls = str(m, "superCls");
            op.invoke = str(m, "invoke");
            Object req = m.get("required");
            op.required = req == null || Boolean.TRUE.equals(req);
            ops.add(op);
        }
        Map<String, Object> result = DexEdit.run(args[1], args[2], ops);
        System.out.println(Json.write(result));
        if (!Boolean.TRUE.equals(result.get("ok"))) System.exit(2);
    }

    private static void runDexInject(String[] args) throws Exception {
        requireArgs(args, 4, "dex-inject-call <in.dex> <out.dex> <spec.json>");
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) Json.parse(readFile(args[3]));
        Inject.Site site = new Inject.Site();
        site.cls = str(m, "cls");
        site.name = str(m, "name");
        site.desc = str(m, "desc");
        site.expectRawSha256 = str(m, "expectRawSha256");
        site.invoke = str(m, "invoke"); // e.g. "Lsgm/offline/Boot;->start()V"
        Object req = m.get("required");
        site.required = req == null || Boolean.TRUE.equals(req);
        Map<String, Object> result = Inject.run(args[1], args[2], site);
        System.out.println(Json.write(result));
        if ("mismatch".equals(result.get("status")) || "missing".equals(result.get("status"))) {
            if (site.required) {
                System.exit(2);
            }
        }
    }

    private static void runDexPatch(String[] args) throws Exception {
        requireArgs(args, 4, "dex-patch <in.dex> <out.dex> <spec.json>");
        String text = readFile(args[3]);
        Object parsed = Json.parse(text);
        @SuppressWarnings("unchecked")
        Map<String, Object> spec = (Map<String, Object>) parsed;
        @SuppressWarnings("unchecked")
        List<Object> rawSites = (List<Object>) spec.get("sites");
        if (rawSites == null) {
            throw new IllegalArgumentException("spec has no 'sites' array");
        }
        List<Patch.Site> sites = new ArrayList<>();
        for (Object o : rawSites) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) o;
            Patch.Site s = new Patch.Site();
            s.cls = str(m, "cls");
            s.name = str(m, "name");
            s.desc = str(m, "desc");
            s.expectRawSha256 = str(m, "expectRawSha256");
            String stub = str(m, "stub");
            if (stub == null) throw new IllegalArgumentException("site missing 'stub'");
            s.stub = Patch.Stub.valueOf(stub);
            s.superCls = str(m, "superCls");
            Object req = m.get("required");
            s.required = req == null || Boolean.TRUE.equals(req);
            sites.add(s);
        }
        Map<String, Object> result = Patch.run(args[1], args[2], sites);
        System.out.println(Json.write(result));
    }

    private static void runDexCompare(String[] args) throws Exception {
        requireArgs(args, 4, "dex-compare <a.dex> <b.dex> <exclude.json>");
        Object parsed = Json.parse(readFile(args[3]));
        @SuppressWarnings("unchecked")
        List<Object> raw = (List<Object>) parsed;
        List<String> excludeKeys = new ArrayList<>();
        for (Object o : raw) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) o;
            excludeKeys.add(str(m, "cls") + "->" + str(m, "name") + str(m, "desc"));
        }
        Map<String, Object> result = Compare.run(args[1], args[2], excludeKeys);
        System.out.println(Json.write(result));
        if (!Boolean.TRUE.equals(result.get("ok"))) {
            System.exit(3);
        }
    }

    private static void runSign(String[] args) throws Exception {
        Map<String, String> opts = parseOpts(args);
        String in = reqOpt(opts, "in");
        String out = reqOpt(opts, "out");
        String ks = reqOpt(opts, "ks");
        String ksPass = reqOpt(opts, "ks-pass-file");
        String alias = reqOpt(opts, "alias");
        int minSdk = opts.containsKey("min-sdk") ? Integer.parseInt(opts.get("min-sdk")) : 24;
        Map<String, Object> result = Sign.sign(in, out, ks, ksPass, alias, minSdk);
        System.out.println(Json.write(result));
    }

    private static void runVerify(String[] args) throws Exception {
        // verify <apk> [--min-sdk N]
        String apk = null;
        int minSdk = 24;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--min-sdk")) {
                minSdk = Integer.parseInt(args[++i]);
            } else {
                apk = args[i];
            }
        }
        if (apk == null) throw new IllegalArgumentException("verify <apk> [--min-sdk N]");
        Map<String, Object> result = Sign.verify(apk, minSdk);
        System.out.println(Json.write(result));
    }

    // ---- small helpers -----------------------------------------------------

    private static Map<String, String> parseOpts(String[] args) {
        Map<String, String> opts = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--")) {
                String key = a.substring(2);
                String value = (i + 1 < args.length && !args[i + 1].startsWith("--"))
                        ? args[++i] : "true";
                opts.put(key, value);
            }
        }
        return opts;
    }

    private static String reqOpt(Map<String, String> opts, String key) {
        String v = opts.get(key);
        if (v == null) throw new IllegalArgumentException("missing --" + key);
        return v;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    private static String readFile(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    private static Map<String, Object> mapOf(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    private static void requireArgs(String[] args, int n, String usage) {
        if (args.length < n) {
            throw new IllegalArgumentException("usage: " + usage);
        }
    }
}
