package org.telegram.messenger;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Locale;

/** Immutable identity of one published APK. Also used for cached files and installation sessions. */
public final class ForkUpdatePolicy {
    public final long code;
    public final long size;
    public final String sha256;

    public ForkUpdatePolicy(long code, long size, String digest) {
        if (code <= 0 || code > 2_100_000_000L || size <= 0 || size > 256L * 1024 * 1024
                || digest == null || !digest.matches("sha256:[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("Invalid release identity");
        }
        this.code = code;
        this.size = size;
        sha256 = digest.substring(7).toLowerCase(Locale.US);
    }

    public String fileName() {
        return "fork-update-" + code + "-" + sha256 + ".apk";
    }

    public static boolean isSavedUpdate(String name) {
        return "fork-update.apk".equals(name) || name.matches("fork-update-[0-9]+(?:-[0-9a-f]{64})?\\.apk");
    }

    public void requireNewer(long installed, long newestKnown) throws IOException {
        if (code <= installed) throw new IOException("当前版本 " + installed + " 不低于目标版本 " + code + "，请重新检查更新。");
        if (code < newestKnown) throw new IOException("该更新提示已过期，请重新检查最新版本。");
    }

    public void requirePackage(String expectedPackage, String actualPackage, long actualCode) throws IOException {
        if (!expectedPackage.equals(actualPackage)) throw new IOException("安装包包名与当前应用不一致，已停止安装。");
        if (actualCode != code) throw new IOException("安装包内部版本 " + actualCode + " 与目标版本 " + code + " 不一致，已停止安装。");
    }

    public void verify(File file) throws Exception {
        if (!file.isFile() || file.length() != size) throw new IOException("安装包大小不正确，请重新下载。");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        requireDigest(digest.digest());
    }

    public void requireDigest(byte[] actual) throws IOException {
        byte[] expected = new byte[32];
        for (int i = 0; i < expected.length; i++) expected[i] = (byte) Integer.parseInt(sha256.substring(i * 2, i * 2 + 2), 16);
        if (!MessageDigest.isEqual(expected, actual)) throw new IOException("安装包 SHA-256 校验失败，请重新下载。");
    }
}
