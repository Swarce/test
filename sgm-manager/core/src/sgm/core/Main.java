package sgm.core;

import java.io.File;
import java.io.FileInputStream;
import java.util.Map;

/**
 * Desktop entry point (same server the Android manager embeds):
 * java -jar sgm-core.jar --content DIR|ZIP [--data DIR] [--port 443] [--http] [--admin-port 8080] [--config FILE] [-v]
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        String content = "content";
        String data = "sgm-data";
        String host = null;
        String config = null;
        int port = 443;
        int adminPort = 8080;
        boolean tls = true;
        for (int k = 0; k < args.length; k++) {
            String a = args[k];
            if (a.equals("--content")) {
                content = args[++k];
            } else if (a.equals("--data")) {
                data = args[++k];
            } else if (a.equals("--host")) {
                host = args[++k];
            } else if (a.equals("--port")) {
                port = Integer.parseInt(args[++k]);
            } else if (a.equals("--admin-port")) {
                adminPort = Integer.parseInt(args[++k]);
            } else if (a.equals("--config")) {
                config = args[++k];
            } else if (a.equals("--http")) {
                tls = false;
            } else if (a.equals("-v") || a.equals("--verbose")) {
                Log.verbose = true;
            } else {
                System.err.println("usage: --content DIR|ZIP [--data DIR] [--host ADDR] [--port N] [--http] [--admin-port N] [--config FILE] [-v]");
                System.exit(2);
            }
        }
        File dataDir = new File(data);
        Map<String, Object> overrides = Admin.loadConfig(dataDir);
        if (config != null && new File(config).isFile()) {
            Map<String, Object> m = Json.map(Json.parse(Content.read(new FileInputStream(config))));
            if (overrides == null) {
                overrides = m;
            } else {
                overrides.putAll(m);
            }
        }
        Server server = Server.open(new File(content), dataDir, overrides);
        HttpServer api = new HttpServer("api", server, tls ? Tls.serverContext(dataDir, Protocol.API_HOST) : null);
        api.start(host, port);
        if (adminPort > 0) {
            Admin admin = new Admin(server, dataDir, null);
            int p = new HttpServer("admin", admin, null).start("127.0.0.1", adminPort);
            Log.i("account editor: http://127.0.0.1:" + p + "/admin/?token=" + admin.token);
        }
        Thread.currentThread().join();
    }
}
