package sgm.manager;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import sgm.core.Json;
import sgm.core.Log;
import sgm.core.extract.UnityExtractor;

/** Builds the server's game data from the installed Skullgirls APK (no PC needed). */
final class GameImporter {
    private GameImporter() {
    }

    static String run(Context c) throws Exception {
        ApplicationInfo ai;
        try {
            ai = c.getPackageManager().getApplicationInfo(Engine.GAME_PACKAGE, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return "Skullgirls is not installed on this phone.";
        }
        List<File> apks = new ArrayList<File>();
        apks.add(new File(ai.sourceDir));
        if (ai.splitSourceDirs != null) {
            for (String s : ai.splitSourceDirs) {
                apks.add(new File(s));
            }
        }
        Map<String, Object> layouts = Json.map(Json.parse(new String(Engine.readAll(c.getAssets().open("typetrees.json")), "UTF-8")));
        Engine e = Engine.get(c);
        File tmp = new File(c.getFilesDir(), "content.new");
        deleteTree(tmp);
        long t0 = System.currentTimeMillis();
        int n = UnityExtractor.extractTo(apks, layouts, tmp, c.getCacheDir(), new UnityExtractor.Progress() {
            @Override
            public void update(String message) {
                Log.i(message);
            }
        });
        install(e, tmp);
        e.server();
        String version = c.getPackageManager().getPackageInfo(Engine.GAME_PACKAGE, 0).versionName;
        e.prefs().edit().putString("contentVersion", version).apply();
        return "Imported " + n + " game objects from Skullgirls " + version + " in "
                + (System.currentTimeMillis() - t0) / 1000 + " s.";
    }

    /** Replaces the content directory with a freshly built one and drops the loaded server. */
    static void install(Engine e, File fresh) throws IOException {
        synchronized (e) {
            e.unload();
            File old = new File(e.contentDir.getPath() + ".old");
            deleteTree(old);
            if (e.contentDir.exists() && !e.contentDir.renameTo(old)) {
                throw new IOException("cannot replace " + e.contentDir);
            }
            if (!fresh.renameTo(e.contentDir)) {
                throw new IOException("cannot install " + fresh);
            }
            deleteTree(old);
        }
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteTree(k);
            }
        }
        if (f.exists() && !f.delete()) {
            Log.w("could not delete " + f);
        }
    }
}
