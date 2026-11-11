package org.telegram.messenger;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.DialogInterface;
import android.content.pm.PackageInfo;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.ForkUpdateSheet;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** Releases are produced by the fork's signed-release workflow. */
public final class ForkUpdater {
    private static final String REPOSITORY = "feiyang1128/Telegram";
    private static final AtomicBoolean checking = new AtomicBoolean();
    private static final AtomicBoolean downloading = new AtomicBoolean();

    public static boolean isEnabled() {
        return true;
    }

    public static void check(Activity activity, boolean force, Browser.Progress progress) {
        SharedPreferences prefs = activity.getSharedPreferences("fork_updates", 0);
        if ((!force && System.currentTimeMillis() - prefs.getLong("last_success", 0) < 60 * 60 * 1000L)
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
            String changelog = null;
            long downloadSize = 0;
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
                            changelog = release.optString("body");
                            downloadSize = asset.optLong("size");
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
            final String changes = changelog;
            final long size = downloadSize;
            final String failure = error;
            AndroidUtilities.runOnUIThread(() -> {
                checking.set(false);
                if (progress != null) progress.end();
                if (cancelled.get() || activity.isFinishing() || activity.isDestroyed()) return;
                if (url != null) {
                    new ForkUpdateSheet(activity, title, size, changes,
                            () -> downloadUpdate(activity, url, title)).show();
                } else if (force) {
                    Toast.makeText(activity, failure != null ? failure : "当前已是最新版本", Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private static void downloadUpdate(Activity activity, String url, String title) {
        if (!downloading.compareAndSet(false, true)) {
            Toast.makeText(activity, "更新正在下载中", Toast.LENGTH_SHORT).show();
            return;
        }
        AtomicBoolean cancelled = new AtomicBoolean();
        AlertDialog dialog = new AlertDialog(activity, AlertDialog.ALERT_TYPE_LOADING);
        dialog.setTitle("正在下载更新");
        dialog.setMessage(title);
        dialog.setProgress(0);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setButton(DialogInterface.BUTTON_NEGATIVE, "取消", (d, which) -> cancelled.set(true));
        dialog.setOnCancelListener(d -> cancelled.set(true));
        dialog.show();

        // Keep network I/O off Telegram's shared queue. Closing the page cancels the transfer.
        new Thread(() -> {
            File partial = new File(activity.getFilesDir(), "cache/fork-update.apk.part");
            File apk = new File(activity.getFilesDir(), "cache/fork-update.apk");
            HttpURLConnection connection = null;
            boolean success = false;
            try {
                File directory = partial.getParentFile();
                if (!directory.isDirectory() && !directory.mkdirs()) throw new Exception("Cannot create update directory");
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);
                connection.setRequestProperty("Accept-Encoding", "identity");
                connection.setRequestProperty("User-Agent", "Telegram-Fork-Updater");
                if (connection.getResponseCode() != 200) throw new Exception("Download failed");
                long total = connection.getContentLength();
                long loaded = 0;
                long lastProgress = 0;
                try (InputStream input = connection.getInputStream();
                     FileOutputStream output = new FileOutputStream(partial)) {
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while (!cancelled.get() && (count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                        loaded += count;
                        long now = android.os.SystemClock.elapsedRealtime();
                        if (now - lastProgress >= 200) {
                            lastProgress = now;
                            final long bytes = loaded;
                            AndroidUtilities.runOnUIThread(() -> {
                                if (activity.isFinishing() || activity.isDestroyed()) {
                                    cancelled.set(true);
                                } else if (!cancelled.get()) {
                                    dialog.setMessage(title + "\n" + AndroidUtilities.formatFileSize(bytes)
                                            + (total > 0 ? " / " + AndroidUtilities.formatFileSize(total) : ""));
                                    if (total > 0) dialog.setProgress((int) Math.min(100, bytes * 100 / total));
                                }
                            });
                        }
                    }
                }
                if (!cancelled.get()) {
                    if (loaded == 0 || total > 0 && loaded != total) throw new Exception("Incomplete APK");
                    PackageInfo info = activity.getPackageManager().getPackageArchiveInfo(partial.getAbsolutePath(), 0);
                    long installed = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionCode;
                    if (info == null || !activity.getPackageName().equals(info.packageName) || info.versionCode <= installed) {
                        throw new Exception("Unexpected update package");
                    }
                    if (apk.exists() && !apk.delete()) throw new Exception("Cannot replace APK");
                    if (!partial.renameTo(apk)) throw new Exception("Cannot save APK");
                    success = true;
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (connection != null) connection.disconnect();
                partial.delete();
                final boolean completed = success;
                AndroidUtilities.runOnUIThread(() -> {
                    downloading.set(false);
                    dialog.dismiss();
                    if (cancelled.get() || activity.isFinishing() || activity.isDestroyed()) return;
                    if (completed) {
                        // The install button remains available after returning from unknown-source settings.
                        AlertDialog ready = new AlertDialog.Builder(activity)
                                .setTitle("下载完成")
                                .setMessage("更新已下载。若系统要求，请允许此应用安装更新，然后返回点击安装。")
                                .setPositiveButton("安装", (d, which) -> installUpdate(activity, apk))
                                .setNegativeButton("稍后", null).create();
                        ready.setDismissDialogByButtons(false);
                        ready.setButton(DialogInterface.BUTTON_NEGATIVE, "稍后", (d, which) -> ready.dismiss());
                        ready.show();
                        installUpdate(activity, apk);
                    } else {
                        new AlertDialog.Builder(activity)
                                .setTitle("更新下载失败")
                                .setMessage("请检查网络连接后重试。")
                                .setPositiveButton("重试", (d, which) -> downloadUpdate(activity, url, title))
                                .setNegativeButton("取消", null).show();
                    }
                });
            }
        }, "ForkUpdateDownload").start();
    }

    private static void installUpdate(Activity activity, File apk) {
        try {
            AndroidUtilities.openForView(apk, apk.getName(), "application/vnd.android.package-archive", activity, null, false);
        } catch (Exception e) {
            FileLog.e(e);
            Toast.makeText(activity, "无法打开安装界面，请稍后重试。", Toast.LENGTH_LONG).show();
        }
    }
}
