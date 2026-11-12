package org.telegram.messenger;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.DialogInterface;
import android.content.pm.PackageInfo;
import android.content.Intent;
import android.content.ClipData;
import android.net.Uri;
import android.os.Build;
import androidx.core.content.FileProvider;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.ForkUpdateSheet;
import org.telegram.ui.Components.AlertsCreator;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;
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
            long updateCode = 0;
            boolean cachedUpdate = false;
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
                    updateCode = code;
                    JSONArray assets = release.getJSONArray("assets");
                    String name = "fork-" + code + "-arm64.apk";
                    String expected = "https://github.com/" + REPOSITORY + "/releases/download/" + tag + "/" + name;
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        if (name.equals(asset.optString("name")) && expected.equals(asset.optString("browser_download_url"))) {
                            download = expected;
                            version = release.optString("name", tag) + " (" + code + ")";
                            changelog = "当前内部版本：" + installed + "\n目标内部版本：" + code + "\n\n" + release.optString("body");
                            downloadSize = asset.optLong("size");
                            break;
                        }
                    }
                    if (download == null) throw new Exception("APK missing");
                    File cached = updateFile(activity, code);
                    if (cached.isFile() && (downloadSize <= 0 || cached.length() == downloadSize)) {
                        PackageInfo info = activity.getPackageManager().getPackageArchiveInfo(cached.getAbsolutePath(), 0);
                        cachedUpdate = info != null && activity.getPackageName().equals(info.packageName)
                                && info.versionCode == code;
                    }
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
            final long code = updateCode;
            final boolean readyToInstall = cachedUpdate;
            final String failure = error;
            AndroidUtilities.runOnUIThread(() -> {
                checking.set(false);
                if (progress != null) progress.end();
                if (cancelled.get() || activity.isFinishing() || activity.isDestroyed()) return;
                if (url != null) {
                    if (readyToInstall) {
                        showInstallReady(activity, updateFile(activity, code), title, false);
                    } else {
                        new ForkUpdateSheet(activity, title, size, changes,
                                () -> downloadUpdate(activity, url, title, code, size)).show();
                    }
                } else if (force) {
                    Toast.makeText(activity, failure != null ? failure : "当前已是最新版本", Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private static File updateFile(Activity activity, long code) {
        return new File(activity.getFilesDir(), "cache/fork-update-" + code + ".apk");
    }

    private static void downloadUpdate(Activity activity, String url, String title, long code, long expectedSize) {
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
            File apk = updateFile(activity, code);
            File partial = new File(apk.getAbsolutePath() + ".part");
            HttpURLConnection connection = null;
            boolean success = false;
            String failure = "无法保存更新文件，请检查可用存储空间后重试。";
            try {
                File directory = partial.getParentFile();
                if (!directory.isDirectory() && !directory.mkdirs()) throw new Exception("Cannot create update directory");
                failure = "无法连接 GitHub 下载服务器。更新下载需要可访问 GitHub 的网络，Telegram 内置代理不会自动用于此下载。";
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(60000);
                connection.setRequestProperty("Accept-Encoding", "identity");
                connection.setRequestProperty("User-Agent", "Telegram-Fork-Updater");
                int status = connection.getResponseCode();
                if (status != HttpURLConnection.HTTP_OK) {
                    failure = "GitHub 下载服务器返回 HTTP " + status + "，请稍后重试或使用浏览器下载。";
                    throw new Exception("Download failed: HTTP " + status);
                }
                long total = connection.getContentLength();
                long loaded = 0;
                long lastProgress = 0;
                failure = "更新下载中断，或无法写入文件。请检查网络连接和可用存储空间，或使用浏览器下载。";
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
                    if (loaded == 0 || total > 0 && loaded != total || expectedSize > 0 && loaded != expectedSize) {
                        failure = "下载文件不完整，请重试或使用浏览器下载。";
                        throw new Exception("Incomplete APK");
                    }
                    failure = "无法读取下载的安装包，请重试或使用浏览器下载。";
                    PackageInfo info = activity.getPackageManager().getPackageArchiveInfo(partial.getAbsolutePath(), 0);
                    long installed = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionCode;
                    if (info == null) throw new Exception("Invalid update APK");
                    if (!activity.getPackageName().equals(info.packageName)) {
                        failure = "下载的安装包与当前应用包名不匹配，无法覆盖安装。";
                        throw new Exception("Update package name mismatch");
                    }
                    if (info.versionCode != code) {
                        failure = "下载包内部版本为 " + info.versionCode + "，与目标版本 " + code + " 不一致，已停止安装。请重新检查更新。";
                        throw new Exception("Update APK version does not match release");
                    }
                    if (info.versionCode <= installed) {
                        failure = "下载的版本不高于当前已安装版本，无需重复安装。请重新检查更新。";
                        throw new Exception("Update is not newer than installed version");
                    }
                    failure = "无法保存更新文件，请检查可用存储空间后重试。";
                    if (apk.exists() && !apk.delete()) throw new Exception("Cannot replace APK");
                    if (!partial.renameTo(apk)) throw new Exception("Cannot save APK");
                    success = true;
                }
            } catch (SocketTimeoutException e) {
                failure = "连接 GitHub 下载服务器或接收文件超时。请检查网络后重试，或使用浏览器下载。";
                FileLog.e(e);
            } catch (UnknownHostException e) {
                failure = "无法解析 GitHub 下载服务器地址，请检查网络或 DNS，或使用浏览器下载。";
                FileLog.e(e);
            } catch (SSLException e) {
                failure = "与 GitHub 下载服务器建立安全连接失败。请检查网络和设备时间，或使用浏览器下载。";
                FileLog.e(e);
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (connection != null) connection.disconnect();
                partial.delete();
                final boolean completed = success;
                final String failureMessage = failure;
                AndroidUtilities.runOnUIThread(() -> {
                    downloading.set(false);
                    dialog.dismiss();
                    if (cancelled.get() || activity.isFinishing() || activity.isDestroyed()) return;
                    if (completed) {
                        showInstallReady(activity, apk, title, true);
                    } else {
                        new AlertDialog.Builder(activity)
                                .setTitle("更新下载失败")
                                .setMessage(failureMessage)
                                .setPositiveButton("重试", (d, which) -> downloadUpdate(activity, url, title, code, expectedSize))
                                .setNeutralButton("浏览器下载", (d, which) -> Browser.openUrl(activity,
                                        android.net.Uri.parse(url), false, true, true, null, null, true, false, false))
                                .setNegativeButton("取消", null).show();
                    }
                });
            }
        }, "ForkUpdateDownload").start();
    }

    private static void showInstallReady(Activity activity, File apk, String title, boolean openInstaller) {
        // Keep this available after returning from installation settings or cancelling the installer.
        AlertDialog ready = new AlertDialog.Builder(activity)
                .setTitle("更新已下载")
                .setMessage(title + "\n安装完成后版本才会更新。若系统要求，请允许此应用安装更新，然后返回点击安装。")
                .setPositiveButton("安装", (d, which) -> installUpdate(activity, apk))
                .setNegativeButton("稍后", null).create();
        ready.setDismissDialogByButtons(false);
        ready.setButton(DialogInterface.BUTTON_NEGATIVE, "稍后", (d, which) -> ready.dismiss());
        ready.show();
        if (openInstaller) installUpdate(activity, apk);
    }

    private static void installUpdate(Activity activity, File apk) {
        try {
            if (!apk.isFile()) {
                throw new Exception("Downloaded update is missing");
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.getPackageManager().canRequestPackageInstalls()) {
                AlertsCreator.createApkRestrictedDialog(activity, null).show();
                return;
            }
            Uri uri = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                    ? FileProvider.getUriForFile(activity, activity.getPackageName() + ".provider", apk)
                    : Uri.fromFile(apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(ClipData.newRawUri("update", uri));
            // The general file viewer retries failures as text/plain, which hides installation errors.
            activity.startActivity(intent);
        } catch (Exception e) {
            FileLog.e(e);
            new AlertDialog.Builder(activity)
                    .setTitle("无法打开安装界面")
                    .setMessage("请确认已允许此应用安装未知来源应用，并检查系统安装器是否可用。下载的安装包已保留，可稍后重新检查更新并点击安装。")
                    .setPositiveButton("确定", null).show();
        }
    }
}
