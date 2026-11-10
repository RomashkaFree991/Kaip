package ru.kaip.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

/**
 * Kaip — оболочка над веб-панелью сервера.
 * Открывает панель, даёт выбирать файлы из памяти телефона (загрузка на сервер)
 * и сохраняет скачанные с сервера файлы в «Загрузки/Kaip».
 */
public class MainActivity extends Activity {
    private static final int REQ_FILE = 1;
    private static final int REQ_STORAGE = 2;
    private static final String PREF_URL = "server_url";

    private WebView web;
    private SharedPreferences prefs;
    private ValueCallback<Uri[]> fileCallback;
    private String[] pendingDownload;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("kaip", MODE_PRIVATE);
        getWindow().setStatusBarColor(0xFF14161C);
        getWindow().setNavigationBarColor(0xFF181A21);

        web = new WebView(this);
        web.setBackgroundColor(0xFF14161C);
        setContentView(web);
        setupWebView();

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else if (serverUrl().isEmpty()) {
            askServer(false);
        } else {
            web.loadUrl(serverUrl());
        }
    }

    private String serverUrl() {
        return prefs.getString(PREF_URL, "");
    }

    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setTextZoom(100);
        s.setAllowFileAccess(false);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new Bridge(), "KaipApp");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                Uri server = Uri.parse(serverUrl());
                if (server.getHost() != null && server.getHost().equalsIgnoreCase(url.getHost())) {
                    return false;
                }
                // Чужие ссылки открываем в обычном браузере.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, url));
                } catch (ActivityNotFoundException ignored) {
                }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showError(String.valueOf(error.getDescription()));
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                CookieManager.getInstance().flush();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;
                Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("*/*");
                pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                try {
                    startActivityForResult(Intent.createChooser(pick, "Выбери файл"), REQ_FILE);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    toast("Нет приложения для выбора файлов");
                    return false;
                }
                return true;
            }
        });

        web.setDownloadListener((url, userAgent, contentDisposition, mimeType, length) ->
                download(url, userAgent, contentDisposition, mimeType));
    }

    // ------------------------------------------------------------ сервер

    private void askServer(boolean cancelable) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://1-2-3-4.sslip.io");
        input.setText(serverUrl());
        input.setSingleLine(true);
        FrameLayout box = new FrameLayout(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Адрес сервера")
                .setMessage("Адрес, который показал install.sh в конце установки.")
                .setView(box)
                .setCancelable(cancelable)
                .setPositiveButton("Подключиться", (d, w) -> {
                    String url = normalize(input.getText().toString());
                    if (url.isEmpty()) {
                        askServer(cancelable);
                        return;
                    }
                    prefs.edit().putString(PREF_URL, url).apply();
                    web.clearHistory();
                    web.loadUrl(url);
                });
        if (cancelable) {
            b.setNegativeButton("Отмена", null);
        }
        b.show();
    }

    private static String normalize(String raw) {
        String url = raw.trim();
        if (url.isEmpty()) {
            return "";
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url + "/";
    }

    private void showError(String description) {
        String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{background:#14161c;color:#e8eaf0;font-family:sans-serif;display:flex;"
                + "flex-direction:column;align-items:center;justify-content:center;height:90vh;text-align:center;padding:16px}"
                + "button{margin:6px;padding:14px 20px;border:0;border-radius:12px;font-size:16px;font-weight:600}"
                + ".a{background:#5b8cff;color:#fff}.b{background:#252937;color:#e8eaf0}p{color:#8b91a1}</style></head><body>"
                + "<h2>Нет связи с сервером</h2><p>" + escape(description) + "<br>" + escape(serverUrl()) + "</p>"
                + "<div><button class='a' onclick='KaipApp.retry()'>Повторить</button>"
                + "<button class='b' onclick='KaipApp.openSettings()'>Сменить адрес</button></div></body></html>";
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ------------------------------------------------------------ файлы

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE || fileCallback == null) {
            return;
        }
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                result = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) {
                    result[i] = clip.getItemAt(i).getUri();
                }
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    private void download(String url, String userAgent, String contentDisposition, String mimeType) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingDownload = new String[]{url, userAgent, contentDisposition, mimeType};
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
        Uri uri = Uri.parse(url);
        String fromQuery = uri.getQueryParameter("path");
        if (fromQuery != null && !fromQuery.isEmpty()) {
            name = fromQuery.substring(fromQuery.lastIndexOf('/') + 1);
        }
        try {
            DownloadManager.Request r = new DownloadManager.Request(uri);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) {
                r.addRequestHeader("Cookie", cookie);
            }
            r.addRequestHeader("User-Agent", userAgent);
            r.setTitle(name);
            r.setMimeType(mimeType);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Kaip/" + name);
            ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
            toast("Скачивается в «Загрузки/Kaip»: " + name);
        } catch (Exception e) {
            toast("Не удалось скачать: " + e.getMessage());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQ_STORAGE && pendingDownload != null) {
            String[] d = pendingDownload;
            pendingDownload = null;
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                download(d[0], d[1], d[2], d[3]);
            } else {
                toast("Без разрешения файл не сохранить");
            }
        }
    }

    // ------------------------------------------------------------ экран

    private void setScreenMode(boolean on) {
        setRequestedOrientation(on ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        if (on) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    /** Методы, доступные странице как window.KaipApp. */
    private class Bridge {
        @JavascriptInterface
        public void onScreen(boolean on) {
            runOnUiThread(() -> setScreenMode(on));
        }

        @JavascriptInterface
        public void openSettings() {
            runOnUiThread(() -> askServer(true));
        }

        @JavascriptInterface
        public void retry() {
            runOnUiThread(() -> {
                if (serverUrl().isEmpty()) {
                    askServer(false);
                } else {
                    web.loadUrl(serverUrl());
                }
            });
        }
    }

    // ------------------------------------------------------------ жизненный цикл

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        web.evaluateJavascript("(window.kaipBack && window.kaipBack()) ? 'yes' : 'no'", value -> {
            if (value != null && value.contains("yes")) {
                return;
            }
            if (web.canGoBack()) {
                web.goBack();
            } else {
                MainActivity.super.onBackPressed();
            }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
