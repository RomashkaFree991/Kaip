package ru.kaip.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.net.http.SslCertificate;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

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
    private final List<SslErrorHandler> pendingSsl = new ArrayList<>();
    private AlertDialog sslDialog;

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
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                onSslError(handler, error);
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
        String guessed = URLUtil.guessFileName(url, contentDisposition, mimeType);
        String fromQuery = Uri.parse(url).getQueryParameter("path");
        final String name = (fromQuery != null && !fromQuery.isEmpty())
                ? fromQuery.substring(fromQuery.lastIndexOf('/') + 1) : guessed;
        final String mime = (mimeType == null || mimeType.isEmpty()) ? "application/octet-stream" : mimeType;
        final String cookie = CookieManager.getInstance().getCookie(url);
        toast("Скачиваю: " + name);
        // Качаем сами, а не через DownloadManager: он не знает про собственный сертификат сервера.
        new Thread(() -> {
            try {
                saveDownload(url, userAgent, cookie, name, mime);
                runOnUiThread(() -> toast("Сохранено в «Загрузки/Kaip»: " + name));
            } catch (Exception e) {
                runOnUiThread(() -> toast("Не удалось скачать " + name + ": " + e.getMessage()));
            }
        }).start();
    }

    private void saveDownload(String url, String userAgent, String cookie, String name, String mime) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        if (conn instanceof HttpsURLConnection) {
            ((HttpsURLConnection) conn).setSSLSocketFactory(trustingFactory(Uri.parse(url).getHost()));
        }
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(60000);
        if (cookie != null) {
            conn.setRequestProperty("Cookie", cookie);
        }
        if (userAgent != null) {
            conn.setRequestProperty("User-Agent", userAgent);
        }
        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            throw new IOException("сервер ответил " + code);
        }
        try (InputStream in = conn.getInputStream()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentResolver resolver = getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, name);
                values.put(MediaStore.Downloads.MIME_TYPE, mime);
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Kaip");
                values.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri item = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (item == null) {
                    throw new IOException("нет доступа к «Загрузкам»");
                }
                try (OutputStream out = resolver.openOutputStream(item)) {
                    copy(in, out);
                } catch (Exception e) {
                    resolver.delete(item, null, null);
                    throw e;
                }
                values.clear();
                values.put(MediaStore.Downloads.IS_PENDING, 0);
                resolver.update(item, values, null, null);
            } else {
                @SuppressWarnings("deprecation")
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Kaip");
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    throw new IOException("не удалось создать папку");
                }
                File file = new File(dir, name);
                int dot = name.lastIndexOf('.');
                for (int i = 1; file.exists(); i++) {
                    file = new File(dir, dot > 0 ? name.substring(0, dot) + " (" + i + ")" + name.substring(dot) : name + " (" + i + ")");
                }
                try (OutputStream out = new FileOutputStream(file)) {
                    copy(in, out);
                }
                MediaScannerConnection.scanFile(this, new String[]{file.getAbsolutePath()}, null, null);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
    }

    // ------------------------------------------------------------ собственный сертификат сервера

    private String pinKey(String host) {
        return "pin:" + (host == null ? "" : host.toLowerCase());
    }

    private void onSslError(SslErrorHandler handler, SslError error) {
        String host = Uri.parse(error.getUrl()).getHost();
        String serverHost = Uri.parse(serverUrl()).getHost();
        String fp = fingerprint(error.getCertificate());
        if (host == null || !host.equalsIgnoreCase(serverHost) || fp == null) {
            handler.cancel();
            return;
        }
        String pinned = prefs.getString(pinKey(host), null);
        if (fp.equals(pinned)) {
            handler.proceed();
            return;
        }
        pendingSsl.add(handler);
        if (sslDialog != null && sslDialog.isShowing()) {
            return;
        }
        String title = pinned == null ? "Собственный сертификат сервера" : "Внимание: сертификат сервера изменился!";
        String text = (pinned == null
                ? "Сервер использует свой сертификат (порт 443 был занят). Сверь отпечаток с тем, что показал install.sh в конце установки:"
                : "Отпечаток не совпадает с запомненным. Если ты не переустанавливал сервер — не доверяй. Новый отпечаток:")
                + "\n\n" + fp;
        sslDialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(text)
                .setCancelable(false)
                .setPositiveButton("Совпадает, доверять", (d, w) -> {
                    prefs.edit().putString(pinKey(host), fp).apply();
                    for (SslErrorHandler h : pendingSsl) h.proceed();
                    pendingSsl.clear();
                })
                .setNegativeButton("Отмена", (d, w) -> {
                    for (SslErrorHandler h : pendingSsl) h.cancel();
                    pendingSsl.clear();
                    showError("Сертификат не подтверждён");
                })
                .show();
    }

    @SuppressWarnings("deprecation")
    private static String fingerprint(SslCertificate cert) {
        if (cert == null) {
            return null;
        }
        try {
            byte[] der;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                X509Certificate x = cert.getX509Certificate();
                der = x == null ? null : x.getEncoded();
            } else {
                der = SslCertificate.saveState(cert).getByteArray("x509-certificate");
            }
            return der == null ? null : sha256(der);
        } catch (Exception e) {
            return null;
        }
    }

    /** SHA-256 в том же виде, что печатает openssl: AB:CD:... */
    private static String sha256(byte[] data) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format("%02X", d[i]));
        }
        return sb.toString();
    }

    /** Доверяет системным сертификатам и, дополнительно, подтверждённому пользователем сертификату сервера. */
    private SSLSocketFactory trustingFactory(String host) throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        X509TrustManager system = (X509TrustManager) tmf.getTrustManagers()[0];
        String pin = prefs.getString(pinKey(host), null);
        X509TrustManager tm = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                system.checkClientTrusted(chain, authType);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                try {
                    system.checkServerTrusted(chain, authType);
                } catch (CertificateException e) {
                    try {
                        if (pin != null && chain != null && chain.length > 0 && pin.equals(sha256(chain[0].getEncoded()))) {
                            return;
                        }
                    } catch (Exception ignored) {
                    }
                    throw e;
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return system.getAcceptedIssuers();
            }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{tm}, null);
        return ctx.getSocketFactory();
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
