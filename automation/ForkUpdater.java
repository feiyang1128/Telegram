package org.telegram.messenger;

import android.app.Activity;
import android.content.SharedPreferences;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.AlertDialog;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** Releases are produced by the fork's signed-release workflow. */
public final class ForkUpdater {
    private static final String REPOSITORY = "feiyang1128/Telegram";
    private static final AtomicBoolean checking = new AtomicBoolean();

    public static boolean isEnabled() {
        return true;
    }

    public static void check(Activity activity, boolean force, Browser.Progress progress) {
        SharedPreferences prefs = activity.getSharedPreferences("fork_updates", 0);
        if ((!force && System.currentTimeMillis() - prefs.getLong("last_success", 0) < 6 * 60 * 60 * 1000L)
                || !checking.compareAndSet(false, true)) {
            if (progress != null) progress.end();
            return;
        }
        AtomicBoolean cancelled = new AtomicBoolean();
        if (progress != null) {
            progress.init();
            progress.onCancel(() -> cancelled.set(true));
        }
        Utilities.globalQueue.postRunnable(() -> {
            String download = null;
            String version = null;
            String error = null;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL("https://api.github.com/repos/" + REPOSITORY + "/releases/latest").openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("User-Agent", "Telegram-Fork-Updater");
                if (connection.getResponseCode() != 200) throw new Exception("Release unavailable");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (InputStream input = connection.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    int length;
                    while ((length = input.read(buffer)) != -1) {
                        if (bytes.size() + length > 1024 * 1024) throw new Exception("Response too large");
                        bytes.write(buffer, 0, length);
                    }
                }
                JSONObject release = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
                String tag = release.getString("tag_name");
                if (!tag.matches("fork-[0-9]+") || release.optBoolean("draft") || release.optBoolean("prerelease")) {
                    throw new Exception("Unsupported release");
                }
                long code = Long.parseLong(tag.substring(5));
                long installed = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionCode;
                if (code > installed) {
                    JSONArray assets = release.getJSONArray("assets");
                    String name = "fork-" + code + "-arm64.apk";
                    String expected = "https://github.com/" + REPOSITORY + "/releases/download/" + tag + "/" + name;
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        if (name.equals(asset.optString("name")) && expected.equals(asset.optString("browser_download_url"))) {
                            download = expected;
                            version = release.optString("name", tag);
                            break;
                        }
                    }
                    if (download == null) throw new Exception("APK missing");
                }
                prefs.edit().putLong("last_success", System.currentTimeMillis()).apply();
            } catch (Exception e) {
                error = "GitHub 更新检查失败，请稍后重试。";
            } finally {
                if (connection != null) connection.disconnect();
            }
            final String url = download;
            final String title = version;
            final String failure = error;
            AndroidUtilities.runOnUIThread(() -> {
                checking.set(false);
                if (progress != null) progress.end();
                if (cancelled.get() || activity.isFinishing() || activity.isDestroyed()) return;
                if (url != null) {
                    new AlertDialog.Builder(activity)
                            .setTitle("发现新版本")
                            .setMessage(title + "\n从 " + REPOSITORY + " 下载更新，安装时由系统验证签名。")
                            .setPositiveButton("下载更新", (dialog, which) -> Browser.openUrl(activity, url))
                            .setNegativeButton("稍后", null).show();
                } else if (force) {
                    Toast.makeText(activity, failure != null ? failure : "当前已是最新版本", Toast.LENGTH_LONG).show();
                }
            });
        });
    }
}
