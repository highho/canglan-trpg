package com.canglan.android;

import android.app.Activity;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.util.Log;
import android.webkit.WebSettings;
import android.webkit.WebView;

import com.canglan.api.HttpApiServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MainActivity — 苍岚大陆 Android 单机版：WebView 前端 + 内嵌 Java 后端。
 * 启动流程：解压 assets（data/web）到 filesDir → 后台线程启动 HttpApiServer（随机端口，
 * 仅回环监听）→ WebView 加载 http://127.0.0.1:port/。存档落 filesDir/saves。
 */
public class MainActivity extends Activity {

    private static final String TAG = "Canglan";
    /** assets 版本号：数据或前端更新时递增，触发重新解压 */
    private static final String ASSET_VERSION = "1";

    private WebView webView;
    private HttpApiServer server;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setTextZoom(100);
        webView.loadData(
                "<html><meta charset=\"utf-8\"><body style=\"font-family:sans-serif;color:#1a1a1a\">"
                        + "<p>正在启动苍岚大陆……</p></body></html>",
                "text/html", "utf-8");

        Thread boot = new Thread(() -> {
            try {
                Path base = getFilesDir().toPath();
                extractAssetsIfNeeded(base);
                Path saveDir = base.resolve("saves");
                Files.createDirectories(saveDir);
                server = new HttpApiServer(base.resolve("data"), saveDir, base.resolve("web"));
                server.start(0);   // 端口 0：系统随机分配，避免与其他实例冲突
                final String url = "http://127.0.0.1:" + server.port() + "/";
                Log.i(TAG, "backend ready at " + url);
                runOnUiThread(() -> webView.loadUrl(url));
            } catch (Exception e) {
                Log.e(TAG, "启动失败", e);
                final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                runOnUiThread(() -> webView.loadData(
                        "<html><meta charset=\"utf-8\"><body style=\"font-family:sans-serif;color:#b3261e\">"
                                + "<p>启动失败：" + msg + "</p></body></html>",
                        "text/html", "utf-8"));
            }
        }, "canglan-boot");
        boot.start();
    }

    /** assets 版本未变则跳过；变更则重新解压 data/ 与 web/。 */
    private void extractAssetsIfNeeded(Path base) throws IOException {
        Path marker = base.resolve(".asset-version");
        if (Files.exists(marker)) {
            String current = new String(Files.readAllBytes(marker), StandardCharsets.UTF_8).trim();
            if (ASSET_VERSION.equals(current)) return;
        }
        copyAssetDir("data", base.resolve("data"));
        copyAssetDir("web", base.resolve("web"));
        Files.write(marker, ASSET_VERSION.getBytes(StandardCharsets.UTF_8));
    }

    /** 递归拷贝 assets 子树到内部存储（目录则列举子项，文件则流拷贝）。 */
    private void copyAssetDir(String assetPath, Path target) throws IOException {
        AssetManager am = getAssets();
        String[] entries = am.list(assetPath);
        if (entries == null || entries.length == 0) {
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            try (InputStream in = am.open(assetPath);
                 OutputStream out = Files.newOutputStream(target)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return;
        }
        Files.createDirectories(target);
        for (String child : entries) {
            copyAssetDir(assetPath + "/" + child, target.resolve(child));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (server != null) server.stop();
        if (webView != null) webView.destroy();
    }
}
