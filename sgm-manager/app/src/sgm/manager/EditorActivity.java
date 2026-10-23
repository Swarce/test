package sgm.manager;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.DownloadListener;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** The account editor (served by the embedded server) in a WebView. */
public final class EditorActivity extends Activity {
    private static final int REQ_OPEN = 1;
    private static final int REQ_SAVE = 2;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private byte[] pendingSave;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setTitle("Account editor");
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !url.startsWith("http://127.0.0.1:");
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = cb;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(i, REQ_OPEN);
                return true;
            }
        });
        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(final String url, String ua, final String disposition, String mime, long len) {
                download(url, disposition);
            }
        });
        web.loadData("<p style='font-family:sans-serif;padding:16px'>Loading game data…</p>", "text/html", "utf-8");
        setContentView(web);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String url = Engine.get(EditorActivity.this).editorUrl();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            web.loadUrl(url);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(EditorActivity.this, "Cannot start the editor: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            finish();
                        }
                    });
                }
            }
        }).start();
    }

    /** Save exports through the system file picker (WebView does not handle downloads itself). */
    private void download(final String url, final String disposition) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setRequestProperty("X-Admin-Token", Engine.get(EditorActivity.this).adminToken());
                    final byte[] data = Engine.readAll(c.getInputStream());
                    String name = "sgm-save.json";
                    if (disposition != null && disposition.contains("filename=\"")) {
                        int a = disposition.indexOf("filename=\"") + 10;
                        name = disposition.substring(a, disposition.indexOf('"', a));
                    }
                    final String fname = name;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            pendingSave = data;
                            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                            i.addCategory(Intent.CATEGORY_OPENABLE);
                            i.setType("application/json");
                            i.putExtra(Intent.EXTRA_TITLE, fname);
                            startActivityForResult(i, REQ_SAVE);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(EditorActivity.this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        if (req == REQ_OPEN) {
            if (fileCallback != null) {
                Uri u = result == RESULT_OK && data != null ? data.getData() : null;
                fileCallback.onReceiveValue(u == null ? null : new Uri[]{u});
                fileCallback = null;
            }
        } else if (req == REQ_SAVE && result == RESULT_OK && data != null && data.getData() != null && pendingSave != null) {
            try {
                OutputStream out = getContentResolver().openOutputStream(data.getData());
                try {
                    out.write(pendingSave);
                } finally {
                    out.close();
                }
                Toast.makeText(this, "Save exported", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
            pendingSave = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
