package org.telegram.messenger;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.ForkUpdateSheet;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLException;

/** One immutable release identity follows the APK from GitHub into a system install session. */
public final class ForkUpdater {
    private static final String REPOSITORY = "feiyang1128/Telegram";
    private static final AtomicBoolean checking = new AtomicBoolean();
    private static final AtomicBoolean busy = new AtomicBoolean();
    static final String INSTALL_ACTION = "org.telegram.messenger.FORK_UPDATE_INSTALL";
    private static volatile WeakReference<Activity> resumedActivity = new WeakReference<>(null);
    private static volatile PendingConfirmation pendingConfirmation;
    private static volatile Release permissionTarget;

    private static final class PendingConfirmation {
        final int session;
        final Intent intent;
        PendingConfirmation(int session, Intent intent) { this.session = session; this.intent = intent; }
    }

    private static final class Release {
        final ForkUpdatePolicy apk;
        final String url, title, changes;

        Release(ForkUpdatePolicy apk, String url, String title, String changes) {
            this.apk = apk;
            this.url = url;
            this.title = title + " (" + apk.code + ")";
            this.changes = changes;
        }
    }

    public static boolean isEnabled() { return true; }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("fork_updates", Context.MODE_PRIVATE);
    }

    private static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    private static long installedCode(Context context) throws Exception {
        return versionCode(context.getPackageManager().getPackageInfo(context.getPackageName(), 0));
    }

    private static File updateFile(Context context, Release release) {
        return new File(context.getFilesDir(), "cache/" + release.apk.fileName());
    }

    private static boolean alive(Activity activity) {
        return !activity.isFinishing() && !activity.isDestroyed();
    }

    private static HttpURLConnection connect(String url) throws Exception {
        URL next = new URL(url);
        for (int redirect = 0; redirect <= 5; redirect++) {
            String host = next.getHost();
            if (!"https".equals(next.getProtocol()) || next.getUserInfo() != null
                    || (next.getPort() != -1 && next.getPort() != 443)
                    || !("api.github.com".equals(host) || "github.com".equals(host)
                    || "release-assets.githubusercontent.com".equals(host)
                    || "objects.githubusercontent.com".equals(host))) {
                throw new IOException("更新地址不受支持，已停止下载。");
            }
            HttpURLConnection connection = (HttpURLConnection) next.openConnection();
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(60000);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Cache-Control", "no-cache");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("User-Agent", "Telegram-Fork-Updater");
            try {
                int status = connection.getResponseCode();
                if (status == 200) return connection;
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("更新服务器返回了无效的跳转地址。");
                    next = new URL(next, location);
                } else {
                    throw new IOException("更新服务器返回 HTTP " + status + "，请稍后重试。");
                }
            } catch (Exception e) {
                connection.disconnect();
                throw e;
            }
            connection.disconnect();
        }
        throw new IOException("更新地址跳转次数过多。");
    }

    public static void check(Activity activity, boolean force, Browser.Progress progress) {
        if ((!force && System.currentTimeMillis() - prefs(activity).getLong("last_success", 0) < 3_600_000L)
                || !checking.compareAndSet(false, true)) {
            if (progress != null) progress.end();
            return;
        }
        AtomicBoolean cancelled = new AtomicBoolean();
        if (progress != null) {
            progress.init();
            progress.onCancel(() -> cancelled.set(true));
        }
        new Thread(() -> {
            Release result = null;
            boolean cached = false;
            String failure = null;
            HttpURLConnection connection = null;
            try {
                connection = connect("https://api.github.com/repos/" + REPOSITORY + "/releases/latest");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (InputStream input = connection.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (cancelled.get()) return;
                        if (bytes.size() + count > 1024 * 1024) throw new IOException("更新信息过大。");
                        bytes.write(buffer, 0, count);
                    }
                }
                JSONObject json = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
                String tag = json.getString("tag_name");
                if (!tag.matches("fork-[0-9]+") || json.optBoolean("draft") || json.optBoolean("prerelease")) {
                    throw new IOException("更新信息格式不受支持。");
                }
                long code = Long.parseLong(tag.substring(5));
                long installed = installedCode(activity);
                if (code > installed) {
                    String name = "fork-" + code + "-arm64.apk";
                    String url = "https://github.com/" + REPOSITORY + "/releases/download/" + tag + "/" + name;
                    JSONArray assets = json.getJSONArray("assets");
                    Release parsed = null;
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        if (name.equals(asset.optString("name")) && url.equals(asset.optString("browser_download_url"))) {
                            ForkUpdatePolicy identity = new ForkUpdatePolicy(code, asset.getLong("size"), asset.getString("digest"));
                            parsed = new Release(identity, url, json.optString("name", tag),
                                    "当前内部版本：" + installed + "\n目标内部版本：" + code + "\n\n" + json.optString("body"));
                            break;
                        }
                    }
                    if (parsed == null) throw new IOException("正式版本缺少可验证的 APK 附件。");
                    parsed.apk.requireNewer(installed, prefs(activity).getLong("newest_code", 0));
                    prefs(activity).edit().putLong("newest_code", code).apply();
                    File file = updateFile(activity, parsed);
                    if (file.isFile()) {
                        try { validate(activity, parsed, file); cached = true; }
                        catch (Exception e) { FileLog.e(e); }
                    }
                    // Publish a target only after the complete metadata was validated.
                    result = parsed;
                }
                prefs(activity).edit().putLong("last_success", System.currentTimeMillis()).apply();
            } catch (Exception e) {
                FileLog.e(e);
                failure = errorMessage(e);
            } finally {
                if (connection != null) connection.disconnect();
                checking.set(false);
                if (progress != null) AndroidUtilities.runOnUIThread(progress::end);
            }
            Release release = result;
            boolean ready = cached;
            String error = failure;
            AndroidUtilities.runOnUIThread(() -> {
                if (cancelled.get() || !active(activity)) return;
                if (release != null) {
                    if (ready) showReady(activity, release, false);
                    else new ForkUpdateSheet(activity, release.title, release.apk.size, release.changes,
                            () -> download(activity, release)).show();
                } else if (force) {
                    Toast.makeText(activity, error == null ? "当前已是最新版本" : error, Toast.LENGTH_LONG).show();
                }
            });
        }, "ForkUpdateCheck").start();
    }

    private static void requireCurrent(Context context, Release release) throws Exception {
        release.apk.requireNewer(installedCode(context), prefs(context).getLong("newest_code", 0));
    }

    private static void validate(Context context, Release release, File file) throws Exception {
        requireCurrent(context, release);
        release.apk.verify(file);
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageManager manager = context.getPackageManager();
        PackageInfo candidate = manager.getPackageArchiveInfo(file.getAbsolutePath(), flags);
        PackageInfo installed = manager.getPackageInfo(context.getPackageName(), flags);
        if (candidate == null) throw new IOException("无法读取安装包，请重新下载。");
        release.apk.requirePackage(context.getPackageName(), candidate.packageName, versionCode(candidate));
        Signature[] candidateSignatures = Build.VERSION.SDK_INT >= 28
                ? candidate.signingInfo == null ? null : candidate.signingInfo.getApkContentsSigners() : candidate.signatures;
        Signature[] installedSignatures = Build.VERSION.SDK_INT >= 28
                ? installed.signingInfo == null ? null : installed.signingInfo.getApkContentsSigners() : installed.signatures;
        if (candidateSignatures == null || installedSignatures == null || candidateSignatures.length == 0
                || !new HashSet<>(Arrays.asList(candidateSignatures)).equals(new HashSet<>(Arrays.asList(installedSignatures)))) {
            throw new IOException("安装包签名与当前应用不一致，已停止安装。");
        }
        release.apk.requireNewer(versionCode(installed), prefs(context).getLong("newest_code", 0));
    }

    private static void download(Activity activity, Release release) {
        if (!busy.compareAndSet(false, true)) {
            Toast.makeText(activity, "更新正在处理，请稍候。", Toast.LENGTH_SHORT).show();
            return;
        }
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<HttpURLConnection> activeConnection = new AtomicReference<>();
        AlertDialog dialog = loading(activity, "正在下载更新", cancelled, activeConnection);
        new Thread(() -> {
            File partial = null;
            HttpURLConnection connection = null;
            String failure = null;
            try {
                requireCurrent(activity, release);
                File apk = updateFile(activity, release);
                File directory = apk.getParentFile();
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建更新目录，请检查存储空间。");
                partial = File.createTempFile("fork-download-", ".part", directory);
                connection = connect(release.url);
                activeConnection.set(connection);
                if (cancelled.get()) throw new IOException("已取消更新下载。");
                long loaded = 0, lastProgress = 0;
                try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(partial)) {
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (cancelled.get() || !alive(activity)) throw new IOException("已取消更新下载。");
                        loaded += count;
                        if (loaded > release.apk.size) throw new IOException("下载大小超过发布记录，已停止下载。");
                        output.write(buffer, 0, count);
                        long now = android.os.SystemClock.elapsedRealtime();
                        if (now - lastProgress >= 200) {
                            lastProgress = now;
                            long bytes = loaded;
                            AndroidUtilities.runOnUIThread(() -> {
                                if (!alive(activity) || cancelled.get()) return;
                                dialog.setMessage(release.title + "\n" + AndroidUtilities.formatFileSize(bytes)
                                        + " / " + AndroidUtilities.formatFileSize(release.apk.size));
                                dialog.setProgress((int) (bytes * 100 / release.apk.size));
                            });
                        }
                    }
                    output.getFD().sync();
                }
                validate(activity, release, partial);
                if (cancelled.get()) throw new IOException("已取消更新下载。");
                // Replacement cannot affect a running installation: the system owns its own session copy.
                if (apk.exists() && !apk.delete()) throw new IOException("无法替换更新文件。");
                if (!partial.renameTo(apk)) throw new IOException("无法保存更新文件。");
                // Only updater-owned files are removed. Install sessions already own independent bytes.
                File[] files = directory.listFiles();
                if (files != null) for (File old : files) {
                    if (!old.equals(apk) && ForkUpdatePolicy.isSavedUpdate(old.getName())) old.delete();
                }
            } catch (Exception e) {
                FileLog.e(e);
                failure = errorMessage(e);
            } finally {
                activeConnection.set(null);
                if (connection != null) connection.disconnect();
                if (partial != null && partial.exists()) partial.delete();
                busy.set(false);
            }
            String error = failure;
            AndroidUtilities.runOnUIThread(() -> {
                dialog.dismiss();
                if (!active(activity) || cancelled.get()) return;
                if (error == null) showReady(activity, release, true);
                else new AlertDialog.Builder(activity).setTitle("更新下载失败").setMessage(error)
                        .setPositiveButton("重试", (d, which) -> download(activity, release))
                        .setNeutralButton("浏览器下载", (d, which) -> openBrowser(activity, release.url))
                        .setNegativeButton("取消", null).show();
            });
        }, "ForkUpdateDownload").start();
    }

    private static AlertDialog loading(Activity activity, String title, AtomicBoolean cancelled,
                                       AtomicReference<HttpURLConnection> activeConnection) {
        AlertDialog dialog = new AlertDialog(activity, AlertDialog.ALERT_TYPE_LOADING);
        dialog.setTitle(title);
        dialog.setCanceledOnTouchOutside(false);
        Runnable cancel = () -> {
            cancelled.set(true);
            HttpURLConnection connection = activeConnection == null ? null : activeConnection.get();
            if (connection != null) new Thread(connection::disconnect, "ForkUpdateCancel").start();
        };
        dialog.setButton(android.content.DialogInterface.BUTTON_NEGATIVE, "取消", (d, which) -> cancel.run());
        dialog.setOnCancelListener(d -> cancel.run());
        dialog.show();
        return dialog;
    }

    private static void showReady(Activity activity, Release release, boolean installNow) {
        AlertDialog ready = new AlertDialog.Builder(activity).setTitle("更新已下载")
                .setMessage(release.title + "\n安装前会重新校验文件，系统仍会要求你确认安装。")
                .setPositiveButton("安装", (d, which) -> install(activity, release))
                .setNegativeButton("稍后", null).create();
        ready.show();
        if (installNow) { ready.dismiss(); install(activity, release); }
    }

    private static void install(Activity activity, Release release) {
        try { requireCurrent(activity, release); }
        catch (Exception e) {
            new AlertDialog.Builder(activity).setTitle("此更新已过期").setMessage(errorMessage(e)).setPositiveButton("确定", null).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            permissionTarget = release;
            AlertsCreator.createApkRestrictedDialog(activity, null).show();
            return; // The immutable cached target is offered again by the next update check.
        }
        PendingConfirmation pending = pendingConfirmation;
        if (pending != null && pending.session == prefs(activity).getInt("install_session", -1)
                && release.apk.code == prefs(activity).getLong("install_code", 0)) {
            try { activity.startActivity(new Intent(pending.intent)); return; }
            catch (Exception e) { FileLog.e(e); pendingConfirmation = null; }
        }
        if (!busy.compareAndSet(false, true)) {
            Toast.makeText(activity, "更新正在处理，请稍候。", Toast.LENGTH_SHORT).show();
            return;
        }
        AtomicBoolean cancelled = new AtomicBoolean();
        AlertDialog dialog = loading(activity, "正在校验并准备安装", cancelled, null);
        new Thread(() -> {
            PackageInstaller installer = activity.getPackageManager().getPackageInstaller();
            int sessionId = -1;
            String failure = null;
            try {
                int previous = prefs(activity).getInt("install_session", -1);
                PackageInstaller.SessionInfo previousInfo = previous < 0 ? null : installer.getSessionInfo(previous);
                if (previousInfo != null) {
                    if (previousInfo.isActive()) throw new IOException("系统正在处理安装，请先完成或取消系统安装确认。");
                    // A user-triggered retry can replace an idle session whose confirmation was lost on process death.
                    installer.abandonSession(previous);
                    PendingConfirmation old = pendingConfirmation;
                    if (old != null && old.session == previous) pendingConfirmation = null;
                }
                File apk = updateFile(activity, release);
                validate(activity, release, apk);
                PackageInstaller.SessionParams parameters = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                parameters.setAppPackageName(activity.getPackageName());
                parameters.setSize(release.apk.size);
                if (Build.VERSION.SDK_INT >= 31) parameters.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
                sessionId = installer.createSession(parameters);
                // Record the session before staging so process death cannot leave an untracked partial install.
                if (!prefs(activity).edit().putInt("install_session", sessionId).putLong("install_code", release.apk.code)
                        .putLong("install_started", System.currentTimeMillis()).remove("install_error").commit()) {
                    throw new IOException("无法记录安装状态，已停止安装。");
                }
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (PackageInstaller.Session session = installer.openSession(sessionId);
                     InputStream input = new FileInputStream(apk);
                     OutputStream output = session.openWrite("base.apk", 0, release.apk.size)) {
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    long copied = 0;
                    while ((count = input.read(buffer)) != -1) {
                        if (cancelled.get() || !active(activity)) throw new IOException("已取消安装准备。");
                        copied += count;
                        if (copied > release.apk.size) throw new IOException("安装文件在读取期间发生变化。");
                        output.write(buffer, 0, count);
                        digest.update(buffer, 0, count);
                    }
                    if (copied != release.apk.size) throw new IOException("安装文件不完整。");
                    release.apk.requireDigest(digest.digest());
                    session.fsync(output);
                }
                requireCurrent(activity, release);
                if (cancelled.get() || !active(activity)) throw new IOException("已取消安装准备。");
                Intent callback = new Intent(activity, ForkUpdateInstallReceiver.class).setAction(INSTALL_ACTION);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent status = PendingIntent.getBroadcast(activity, sessionId, callback, flags);
                try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                    session.commit(status.getIntentSender());
                }
                // No file URI is handed to an OEM file viewer: the system installs these verified session bytes.
                sessionId = -1;
            } catch (Exception e) {
                FileLog.e(e);
                failure = errorMessage(e);
            } finally {
                if (sessionId >= 0) {
                    try { installer.abandonSession(sessionId); } catch (Exception e) { FileLog.e(e); }
                    if (prefs(activity).getInt("install_session", -1) == sessionId) prefs(activity).edit().remove("install_session").remove("install_code").apply();
                }
                busy.set(false);
            }
            String error = failure;
            AndroidUtilities.runOnUIThread(() -> {
                dialog.dismiss();
                if (!active(activity) || cancelled.get()) return;
                if (error != null) new AlertDialog.Builder(activity).setTitle("无法准备更新安装").setMessage(error)
                        .setPositiveButton("确定", null).setNeutralButton("浏览器下载", (d, which) -> openBrowser(activity, release.url)).show();
            });
        }, "ForkUpdateInstall").start();
    }

    static void handleInstallStatus(Context context, Intent intent) {
        if (!INSTALL_ACTION.equals(intent.getAction())) return;
        int session = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
        SharedPreferences state = prefs(context);
        if (session < 0 || session != state.getInt("install_session", -1)) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            try {
                if (confirmation == null) throw new IOException("系统未返回安装确认界面。");
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                pendingConfirmation = new PendingConfirmation(session, new Intent(confirmation));
                Activity foreground = resumedActivity.get();
                if (foreground != null && active(foreground)) foreground.startActivity(new Intent(confirmation));
                else context.startActivity(confirmation);
                return;
            } catch (Exception e) {
                FileLog.e(e);
                try { context.getPackageManager().getPackageInstaller().abandonSession(session); } catch (Exception ignored) { }
                state.edit().remove("install_session").remove("install_code")
                        .putString("install_error", "无法显示系统安装确认，请返回应用重新检查更新并重试。").commit();
                pendingConfirmation = null;
                return;
            }
        }
        SharedPreferences.Editor editor = state.edit().remove("install_session").remove("install_started");
        PendingConfirmation pending = pendingConfirmation;
        if (pending != null && pending.session == session) pendingConfirmation = null;
        if (status != PackageInstaller.STATUS_SUCCESS) {
            editor.remove("install_code").putString("install_error", installError(status)
                    + "\n目标内部版本：" + state.getLong("install_code", 0) + "\n系统状态码：" + status);
        }
        editor.commit();
        FileLog.d("Fork update installation result: session=" + session + " status=" + status);
        Activity activity = resumedActivity.get();
        if (activity != null) AndroidUtilities.runOnUIThread(() -> {
            if (alive(activity) && activity.hasWindowFocus()) reportInstallState(activity);
        }, 300);
    }

    private static String installError(int status) {
        switch (status) {
            case PackageInstaller.STATUS_FAILURE_ABORTED: return "安装已取消，安装包已保留，可再次检查更新后安装。";
            case PackageInstaller.STATUS_FAILURE_CONFLICT: return "系统拒绝覆盖安装，可能存在签名或设备其他用户下的版本冲突。";
            case PackageInstaller.STATUS_FAILURE_STORAGE: return "设备存储空间不足，更新未安装。";
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE: return "系统认为此安装包与设备不兼容，更新未安装。";
            case PackageInstaller.STATUS_FAILURE_INVALID: return "系统判定安装包无效，更新未安装，请重新下载。";
            case PackageInstaller.STATUS_FAILURE_BLOCKED: return "系统策略阻止了安装，请检查安装权限或设备限制。";
            default: return "系统安装失败，安装包已保留，可重试或使用浏览器下载。";
        }
    }

    /** Called when returning from the system installer; a download alone never counts as success. */
    public static void onResume(Activity activity) {
        resumedActivity = new WeakReference<>(activity);
        AndroidUtilities.runOnUIThread(() -> {
            if (alive(activity) && activity.hasWindowFocus()) reportInstallState(activity);
        }, 300);
    }

    public static void onPause(Activity activity) {
        if (resumedActivity.get() == activity) resumedActivity = new WeakReference<>(null);
    }

    private static boolean active(Activity activity) {
        return alive(activity) && resumedActivity.get() == activity;
    }

    private static void reportInstallState(Activity activity) {
        Release permission = permissionTarget;
        if (permission != null && (Build.VERSION.SDK_INT < 26 || activity.getPackageManager().canRequestPackageInstalls())) {
            permissionTarget = null;
            try {
                requireCurrent(activity, permission);
                showReady(activity, permission, false);
            } catch (Exception e) { FileLog.e(e); }
        }
        SharedPreferences state = prefs(activity);
        String failure = state.getString("install_error", null);
        if (failure != null) {
            state.edit().remove("install_error").apply();
            new AlertDialog.Builder(activity).setTitle("更新尚未安装").setMessage(failure).setPositiveButton("确定", null).show();
        }
        long expected = state.getLong("install_code", 0);
        if (expected > 0) {
            try {
                long actual = installedCode(activity);
                if (actual >= expected) {
                    state.edit().remove("install_code").remove("install_session").remove("install_started").remove("last_success").apply();
                    Toast.makeText(activity, "更新已安装，当前内部版本：" + actual, Toast.LENGTH_LONG).show();
                }
            } catch (Exception e) { FileLog.e(e); }
        }
    }

    private static String errorMessage(Exception error) {
        if (error instanceof SocketTimeoutException) return "访问 GitHub 超时，请重试或使用浏览器下载。";
        if (error instanceof UnknownHostException) return "无法解析 GitHub 地址，请检查网络或 DNS。";
        if (error instanceof SSLException) return "安全连接失败，请检查网络和设备时间。";
        return error instanceof IOException && error.getMessage() != null ? error.getMessage()
                : "更新处理失败，请重新检查更新；下载需要可访问 GitHub 的网络。";
    }

    private static void openBrowser(Activity activity, String url) {
        Browser.openUrl(activity, android.net.Uri.parse(url), false, true, true, null, null, true, false, false);
    }
}
