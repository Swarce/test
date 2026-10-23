package sgm.core.extract;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import sgm.core.Json;
import sgm.core.Log;

/** Desktop front end of the on-device importer: java sgm.core.extract.ExtractMain typetrees.json out-dir base.apk [split.apk...] */
public final class ExtractMain {
    private ExtractMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: ExtractMain typetrees.json out-dir base.apk [split.apk...]");
            System.exit(2);
        }
        Map<String, Object> tt = Json.map(Json.parse(sgm.core.Content.read(new FileInputStream(args[0]))));
        List<File> apks = new ArrayList<File>();
        for (int i = 2; i < args.length; i++) {
            apks.add(new File(args[i]));
        }
        long t0 = System.currentTimeMillis();
        int n = UnityExtractor.extractTo(apks, tt, new File(args[1]), new File(System.getProperty("java.io.tmpdir")), new UnityExtractor.Progress() {
            @Override
            public void update(String message) {
                Log.i(message);
            }
        });
        Log.i("wrote " + n + " objects to " + args[1] + " in " + (System.currentTimeMillis() - t0) + " ms");
    }
}
