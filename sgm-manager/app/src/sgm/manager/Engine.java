package sgm.manager;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import sgm.core.Admin;
import sgm.core.Content;
import sgm.core.HttpServer;
import sgm.core.Log;
import sgm.core.Server;

/**
 * Process-wide state: game content, the server and the account editor listener. The VPN service
 * and the activities share one instance.
 */
final class Engine {
    static final String GAME_PACKAGE = "com.autumn.skullgirls";

    private static Engine instance;

    final Context app;
    final File contentDir;
    final File dataDir;
    private Server server;
    private Admin admin;
    private HttpServer adminHttp;
    private int adminPort;

    static synchronized Engine get(Context c) {
        if (instance == null) {
            instance = new Engine(c.getApplicationContext());
        }
        return instance;
    }

    private Engine(Context app) {
        this.app = app;
        contentDir = new File(app.getFilesDir(), "content");
        dataDir = new File(app.getFilesDir(), "data");
        Log.sink = new Log.Sink() {
            @Override
            public void write(char level, String line) {
                int p = level == 'E' ? android.util.Log.ERROR : level == 'W' ? android.util.Log.WARN
                        : level == 'D' ? android.util.Log.DEBUG : android.util.Log.INFO;
                android.util.Log.println(p, "SGM", line);
            }
        };
    }

    SharedPreferences prefs() {
        return app.getSharedPreferences("sgm", Context.MODE_PRIVATE);
    }

    synchronized boolean hasContent() {
        String[] files = contentDir.list();
        if (files == null) {
            return false;
        }
        for (String f : files) {
            if (f.equals("GameSettings.json")) {
                return true;
            }
        }
        return false;
    }

    synchronized boolean isLoaded() {
        return server != null;
    }

    /** Loads content and starts the account editor listener (idempotent). */
    synchronized Server server() throws Exception {
        if (server == null) {
            if (!hasContent()) {
                throw new IOException("game data has not been imported yet");
            }
            long t0 = System.currentTimeMillis();
            Map<String, Object> cfg = Admin.loadConfig(dataDir);
            server = new Server(Content.load(contentDir), new sgm.core.Store(dataDir), withDefaults(cfg));
            admin = new Admin(server, dataDir, new Admin.Assets() {
                @Override
                public byte[] read(String name) throws IOException {
                    return readAll(app.getAssets().open(name));
                }
            });
            adminHttp = new HttpServer("admin", admin, null);
            adminPort = adminHttp.start("127.0.0.1", 0);
            Log.i("game data loaded in " + (System.currentTimeMillis() - t0) + " ms (" + server.content.objs.size() + " objects)");
        }
        return server;
    }

    private static Map<String, Object> withDefaults(Map<String, Object> overrides) {
        Map<String, Object> cfg = Server.defaultConfig();
        if (overrides != null) {
            cfg.putAll(overrides);
        }
        return cfg;
    }

    /** Forgets the loaded server (after new game data was imported). */
    synchronized void unload() {
        if (adminHttp != null) {
            adminHttp.stop();
        }
        adminHttp = null;
        admin = null;
        server = null;
    }

    synchronized String editorUrl() throws Exception {
        server();
        return "http://127.0.0.1:" + adminPort + "/admin/?token=" + admin.token;
    }

    synchronized String adminToken() throws Exception {
        server();
        return admin.token;
    }

    static byte[] readAll(InputStream in) throws IOException {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
