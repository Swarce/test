package sgm.manager;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import sgm.core.Log;
import sgm.core.vpn.PacketEngine;

/** Manager screen: import game data, start/stop the private server, open the account editor. */
public final class MainActivity extends Activity {
    private static final int REQ_VPN = 1;
    private static final int REQ_ZIP = 2;

    private TextView status;
    private TextView log;
    private Button startBtn;
    private CheckBox offline;
    private final Handler handler = new Handler();
    private volatile String busy;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1500);
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        int pad = dp(16);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);

        TextView intro = new TextView(this);
        intro.setText("Runs a private Skullgirls Mobile server on this phone. Only the game's traffic to its "
                + "servers is redirected (through a local VPN); nothing leaves the phone.");
        col.addView(intro);

        status = new TextView(this);
        status.setPadding(0, pad, 0, pad);
        status.setTextSize(15);
        col.addView(status);

        col.addView(button("Import game data from installed Skullgirls", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                importFromGame();
            }
        }));
        col.addView(button("Import game data file (.zip)", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(i, REQ_ZIP);
            }
        }));

        offline = new CheckBox(this);
        offline.setText("Offline-ready: cache game downloads and answer connectivity checks, so the game can run without internet once its files are cached");
        offline.setChecked(Engine.get(this).prefs().getBoolean("offline", false));
        offline.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                Engine.get(MainActivity.this).prefs().edit().putBoolean("offline", on).apply();
                if (SgmVpnService.running) {
                    toast("Stop and start the server to apply");
                }
            }
        });
        col.addView(offline);

        startBtn = button("Start", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startStop();
            }
        });
        col.addView(startBtn);
        col.addView(button("Open account editor", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!Engine.get(MainActivity.this).hasContent()) {
                    toast("Import the game data first");
                    return;
                }
                startActivity(new Intent(MainActivity.this, EditorActivity.class));
            }
        }));
        col.addView(button("Launch Skullgirls", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = getPackageManager().getLaunchIntentForPackage(Engine.GAME_PACKAGE);
                if (i == null) {
                    toast("Skullgirls is not installed");
                } else {
                    startActivity(i);
                }
            }
        }));

        TextView logTitle = new TextView(this);
        logTitle.setText("Log");
        logTitle.setPadding(0, pad, 0, 0);
        logTitle.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(logTitle);
        log = new TextView(this);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextSize(11);
        log.setTextIsSelectable(true);
        col.addView(log);

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);
        setTitle("SGM Manager");
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(tick);
        warmUp();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    /** Loads the game data in the background so the editor and VPN start instantly. */
    private void warmUp() {
        final Engine e = Engine.get(this);
        if (e.hasContent() && !e.isLoaded() && busy == null) {
            background("Loading game data…", new Job() {
                @Override
                public String run() throws Exception {
                    e.server();
                    return null;
                }
            });
        }
    }

    private void refresh() {
        Engine e = Engine.get(this);
        StringBuilder sb = new StringBuilder();
        boolean installed = installed();
        sb.append("Skullgirls: ").append(installed ? "installed" : "NOT installed").append('\n');
        sb.append("Game data: ").append(e.hasContent() ? (e.isLoaded() ? "loaded" : "imported") : "not imported yet").append('\n');
        sb.append("Server: ").append(SgmVpnService.status);
        PacketEngine pe = SgmVpnService.engine;
        if (pe != null) {
            sb.append(" · ").append(pe.dnsAnswered).append(" lookups redirected, ").append(pe.reflected).append(" packets");
        }
        if (SgmVpnService.gateway != null && SgmVpnService.gateway.cdn != null) {
            sb.append("\nDownload cache: ").append(SgmVpnService.gateway.cdn.hits).append(" served from cache, ")
                    .append(SgmVpnService.gateway.cdn.fetched).append(" stored");
        }
        if ("hostname".equals(Compat.privateDnsMode(this))) {
            sb.append("\n\n⚠ Private DNS is set to a specific provider. Set it to Automatic or Off in Settings → "
                    + "Network → Private DNS, otherwise the game bypasses the redirect.");
        }
        if (busy != null) {
            sb.append("\n\n").append(busy);
        }
        status.setText(sb.toString());
        startBtn.setText(SgmVpnService.running ? "Stop server" : "Start server");
        List<String> lines = Log.recent();
        StringBuilder lb = new StringBuilder();
        for (int i = Math.max(0, lines.size() - 60); i < lines.size(); i++) {
            lb.append(lines.get(i)).append('\n');
        }
        log.setText(lb.toString());
    }

    private boolean installed() {
        try {
            getPackageManager().getPackageInfo(Engine.GAME_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ex) {
            return false;
        }
    }

    private void startStop() {
        if (SgmVpnService.running) {
            startService(new Intent(this, SgmVpnService.class).setAction(SgmVpnService.ACTION_STOP));
            return;
        }
        if (!Engine.get(this).hasContent()) {
            toast("Import the game data first");
            return;
        }
        if (!installed()) {
            toast("Install Skullgirls first");
            return;
        }
        Intent prep = VpnService.prepare(this);
        if (prep != null) {
            startActivityForResult(prep, REQ_VPN);
        } else {
            onActivityResult(REQ_VPN, RESULT_OK, null);
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        if (req == REQ_VPN && result == RESULT_OK) {
            startService(new Intent(this, SgmVpnService.class).setAction(SgmVpnService.ACTION_START)
                    .putExtra(SgmVpnService.EXTRA_OFFLINE, offline.isChecked()));
            toast("Server started. Close Skullgirls completely and open it again.");
        } else if (req == REQ_ZIP && result == RESULT_OK && data != null && data.getData() != null) {
            final Uri uri = data.getData();
            background("Importing game data…", new Job() {
                @Override
                public String run() throws Exception {
                    return importZip(uri);
                }
            });
        }
    }

    private void importFromGame() {
        if (SgmVpnService.running) {
            toast("Stop the server first");
            return;
        }
        background("Reading game data from the installed game… (about a minute)", new Job() {
            @Override
            public String run() throws Exception {
                return GameImporter.run(MainActivity.this);
            }
        });
    }

    interface Job {
        String run() throws Exception;
    }

    private void background(final String what, final Job job) {
        if (busy != null) {
            toast("Please wait: " + busy);
            return;
        }
        busy = what;
        refresh();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String msg;
                try {
                    msg = job.run();
                } catch (Throwable t) {
                    Log.e(what + " failed", t);
                    msg = "Failed: " + t.getMessage();
                }
                busy = null;
                final String m = msg;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        refresh();
                        if (m != null) {
                            new AlertDialog.Builder(MainActivity.this).setMessage(m).setPositiveButton("OK", null).show();
                        }
                    }
                });
            }
        }, "sgm-job").start();
    }

    /** Unpacks a content zip (JSON files from tools/extract_content.py) into the content directory. */
    private String importZip(Uri uri) throws Exception {
        if (SgmVpnService.running) {
            return "Stop the server before importing new game data.";
        }
        Engine e = Engine.get(this);
        File tmp = new File(getFilesDir(), "content.new");
        GameImporter.deleteTree(tmp);
        if (!tmp.mkdirs()) {
            throw new IOException("cannot create " + tmp);
        }
        InputStream raw = getContentResolver().openInputStream(uri);
        if (raw == null) {
            throw new IOException("cannot open the file");
        }
        int n = 0;
        ZipInputStream zin = new ZipInputStream(raw);
        try {
            ZipEntry ze;
            byte[] buf = new byte[65536];
            while ((ze = zin.getNextEntry()) != null) {
                String name = ze.getName();
                String base = name.substring(name.lastIndexOf('/') + 1);
                if (ze.isDirectory() || !base.endsWith(".json") || base.startsWith(".")) {
                    continue;
                }
                FileOutputStream out = new FileOutputStream(new File(tmp, base));
                try {
                    int r;
                    while ((r = zin.read(buf)) > 0) {
                        out.write(buf, 0, r);
                    }
                } finally {
                    out.close();
                }
                n++;
            }
        } finally {
            zin.close();
        }
        if (!new File(tmp, "GameSettings.json").isFile()) {
            GameImporter.deleteTree(tmp);
            return "That file does not contain Skullgirls game data (no GameSettings.json).";
        }
        GameImporter.install(e, tmp);
        e.server();
        return "Imported " + n + " data files.";
    }
}
