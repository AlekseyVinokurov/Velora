import app.nimarkogram.messenger.updater.GitHubReleaseClient;
import app.nimarkogram.messenger.updater.VeloraRelease;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;

/** Runs production updater parsing/discovery on a host JVM; never downloads an APK. */
public final class UpdaterDiscoveryCheck {
    public static void main(String[] args) throws Exception {
        String pin = args[0];
        VeloraRelease metadata;
        if (args.length == 3) {
            metadata = VeloraRelease.parse(Files.readString(Path.of(args[1])), pin);
            metadata.validateRelease(new JSONObject(Files.readString(Path.of(args[2]))));
        } else {
            GitHubReleaseClient.Result result = GitHubReleaseClient.latest(pin);
            if (result == null) throw new AssertionError("Updater found no stable release");
            metadata = result.metadata;
        }
        if (!"v0.1.1".equals(metadata.tag) || metadata.versionCode != 100001
                || !"1c73e72079023fd7ed14fbdb7f0ee83ac13752a3".equals(metadata.sourceCommit)
                || !"58650c102147abc3f9d594c246b121911e9a140fc03b79a40f4428ff09b291f1".equals(metadata.sha256)
                || !metadata.isNewerThan(100000, 37) || metadata.isNewerThan(100001, 37)
                || !metadata.acceptsApk(VeloraRelease.PACKAGE_NAME, 100001, "0.1.1", 24, 100000, 37)) {
            throw new AssertionError("Production updater did not accept the authorized successor to 0.1.0");
        }
        System.out.println("PASS: production updater discovers v0.1.1 as an update from 100000, and rejects equal-code offers.");
    }
}
