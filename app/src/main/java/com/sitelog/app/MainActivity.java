package com.sitelog.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {

    private static final int FILE_CHOOSER_REQUEST = 42;
    private WebView web;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window w = getWindow();
        w.setStatusBarColor(Color.parseColor("#26313B"));
        w.setNavigationBarColor(Color.parseColor("#26313B"));

        web = new WebView(this);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // reports are kept in localStorage
        s.setAllowFileAccess(true);            // load the app from assets
        s.setAllowContentAccess(true);
        s.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "Android");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String scheme = u.getScheme();
                if ("file".equals(scheme)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "Choose backup file"), FILE_CHOOSER_REQUEST);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl("file:///android_asset/index.html");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (fileCallback != null) {
                Uri[] result = null;
                if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
                fileCallback.onReceiveValue(result);
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // make sure the last edits are written before the app goes to the background
        web.evaluateJavascript("try{flush()}catch(e){}", null);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    /** Methods the web page calls as window.Android.xxx(...) */
    private class Bridge {

        @JavascriptInterface
        public void shareText(final String text) {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(i, "Share daily summary"));
            });
        }

        @JavascriptInterface
        public void saveFile(final String name, final String base64, final String mime) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");

                // 1) private copy, used for sharing / opening
                File dir = new File(getCacheDir(), "exports");
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, safe);
                try (FileOutputStream out = new FileOutputStream(f)) { out.write(bytes); }
                final Uri shareUri = FileProvider.getUriForFile(MainActivity.this,
                        getPackageName() + ".files", f);

                // 2) public copy in Downloads (Android 10+)
                boolean inDownloads = false;
                if (Build.VERSION.SDK_INT >= 29) {
                    try {
                        ContentResolver cr = getContentResolver();
                        ContentValues v = new ContentValues();
                        v.put(MediaStore.MediaColumns.DISPLAY_NAME, safe);
                        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SiteDailyLog");
                        Uri d = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                        if (d != null) {
                            try (OutputStream os = cr.openOutputStream(d)) {
                                if (os != null) { os.write(bytes); inDownloads = true; }
                            }
                        }
                    } catch (Exception ignored) { }
                }

                final String msg = inDownloads
                        ? safe + "\n\nSaved to Downloads › SiteDailyLog."
                        : safe + "\n\nReady. Use Share to send it or save it to Drive.";
                runOnUiThread(() -> new AlertDialog.Builder(MainActivity.this)
                        .setTitle("File ready")
                        .setMessage(msg)
                        .setPositiveButton("Share", (dlg, which) -> {
                            Intent i = new Intent(Intent.ACTION_SEND);
                            i.setType(mime);
                            i.putExtra(Intent.EXTRA_STREAM, shareUri);
                            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            startActivity(Intent.createChooser(i, "Share file"));
                        })
                        .setNeutralButton("Open", (dlg, which) -> {
                            Intent i = new Intent(Intent.ACTION_VIEW);
                            i.setDataAndType(shareUri, mime);
                            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            try { startActivity(i); }
                            catch (ActivityNotFoundException e) {
                                Toast.makeText(MainActivity.this, "No app found to open this file", Toast.LENGTH_LONG).show();
                            }
                        })
                        .setNegativeButton("Close", null)
                        .show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Could not save the file", Toast.LENGTH_LONG).show());
            }
        }
    }
}
