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
        if (!"v0.1.2".equals(metadata.tag) || metadata.versionCode != 100002
                || !"b2bc4a5781ccbe117c8ba083a06ff9ac9d748898".equals(metadata.sourceCommit)
                || !"c63ae85cf165168d062cafbf7365c4958357179e56e1d8d3481aa5d6e893f389".equals(metadata.sha256)
                || !metadata.isNewerThan(100001, 37) || metadata.isNewerThan(100002, 37)
                || !metadata.acceptsApk(VeloraRelease.PACKAGE_NAME, 100002, "0.1.2", 24, 100001, 37)) {
            throw new AssertionError("Production updater did not accept the authorized successor to 0.1.1");
        }
        System.out.println("PASS: production updater discovers v0.1.2 as an update from 100001, and rejects equal-code offers.");
    }
}
