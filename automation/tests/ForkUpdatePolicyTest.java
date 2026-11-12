import org.telegram.messenger.ForkUpdatePolicy;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Regression checks for stale dialogs, wrong packages/versions, and changed/corrupt APK bytes. */
public final class ForkUpdatePolicyTest {
    private static final String DIGEST = "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    private interface Operation { void run() throws Exception; }
    private static int checks;

    public static void main(String[] args) throws Exception {
        ForkUpdatePolicy target = new ForkUpdatePolicy(7303079, 3, DIGEST);
        target.requireNewer(7303069, 7303079);
        target.requirePackage("app.web", "app.web", 7303079);
        rejects(() -> target.requireNewer(7303079, 7303079), "repeated install after success");
        rejects(() -> target.requireNewer(7303089, 7303079), "downgrade");
        rejects(() -> target.requireNewer(7303069, 7303089), "stale update dialog");
        rejects(() -> target.requirePackage("app.web", "other.web", 7303079), "wrong application");
        rejects(() -> target.requirePackage("app.web", "app.web", 7303069), "older APK behind new release tag");
        rejects(() -> target.requirePackage("app.web", "app.web", 7303089), "unexpected newer APK behind tag");
        rejects(() -> new ForkUpdatePolicy(0, 3, DIGEST), "invalid version");
        rejects(() -> new ForkUpdatePolicy(7303079, 0, DIGEST), "empty artifact");
        rejects(() -> new ForkUpdatePolicy(7303079, Long.MAX_VALUE, DIGEST), "unbounded artifact");
        rejects(() -> new ForkUpdatePolicy(7303079, 3, null), "missing checksum");
        rejects(() -> new ForkUpdatePolicy(7303079, 3, "sha256:bad"), "malformed checksum");
        if (target.fileName().equals(new ForkUpdatePolicy(7303089, 3, DIGEST).fileName())) throw new AssertionError("versions share path");
        if (target.fileName().equals(new ForkUpdatePolicy(7303079, 3, "sha256:" + "0".repeat(64)).fileName())) throw new AssertionError("different artifacts share path");
        if (!ForkUpdatePolicy.isSavedUpdate(target.fileName()) || !ForkUpdatePolicy.isSavedUpdate("fork-update.apk")
                || !ForkUpdatePolicy.isSavedUpdate("fork-update-7303069.apk")
                || ForkUpdatePolicy.isSavedUpdate("other.apk") || ForkUpdatePolicy.isSavedUpdate("fork-update-not-an-update.apk")) {
            throw new AssertionError("cleanup may touch unrelated files or leave legacy updates");
        }

        Path directory = Files.createTempDirectory("fork-update-test");
        File apk = directory.resolve("update.apk").toFile();
        try {
            Files.write(apk.toPath(), "abc".getBytes(StandardCharsets.UTF_8));
            target.verify(apk);
            target.requireDigest(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(apk.toPath())));
            // Same size and advertised version must not allow changed payloads into an install session.
            Files.write(apk.toPath(), "abd".getBytes(StandardCharsets.UTF_8));
            rejects(() -> target.verify(apk), "same-size corrupt cache");
            rejects(() -> target.requireDigest(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(apk.toPath()))), "replacement during session copy");
            Files.write(apk.toPath(), new byte[]{1});
            rejects(() -> target.verify(apk), "truncated download");
            Files.write(apk.toPath(), new byte[4]);
            rejects(() -> target.verify(apk), "oversized download");
            Files.delete(apk.toPath());
            rejects(() -> target.verify(apk), "missing saved APK");
        } finally {
            Files.deleteIfExists(apk.toPath());
            Files.delete(directory);
        }
        if (args.length == 4) {
            new ForkUpdatePolicy(Long.parseLong(args[0]), Long.parseLong(args[1]), args[2]).verify(new File(args[3]));
            System.out.println("Published APK SHA-256 verified");
        }
        System.out.println("Fork update policy tests passed (" + checks + " rejected invalid cases)");
    }

    private static void rejects(Operation operation, String label) throws Exception {
        try { operation.run(); } catch (java.io.IOException | IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("Accepted " + label);
    }
}
