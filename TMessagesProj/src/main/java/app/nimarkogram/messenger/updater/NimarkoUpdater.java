package app.nimarkogram.messenger.updater;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Looper;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;

import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.util.Locale;

public class NimarkoUpdater {

    public static final DispatchQueue otaQueue = new DispatchQueue("nimarkoOtaQueue");

    public static String downloadURL = null;
    public static String version, changelog, size, uploadDate;
    public static int versionCode;
    public static long expectedSizeBytes = 0;
    public static String expectedSha256 = null;
    private static final long MAX_APK_BYTES = 512L * 1024L * 1024L;
    private static final java.util.regex.Pattern SHA256_PATTERN =
            java.util.regex.Pattern.compile("^[0-9a-fA-F]{64}$");

    public static boolean isApkValid(File file) {
        String wantHash = wantedApkHash();
        if (Looper.myLooper() == Looper.getMainLooper()) return false;
        return validateApkFully(file, wantHash);
    }

    private static String wantedApkHash() {
        String wantHash = expectedSha256;
        if (wantHash == null || wantHash.isEmpty()) wantHash = NimarkoUpdateConfig.getApkSha256();
        return wantHash == null ? "" : wantHash.toLowerCase(Locale.ROOT);
    }

    private static boolean validateApkFully(File file, String wantHash) {
        Update update = getOrRestoreLastUpdate();
        if (update == null || file == null || !file.isFile() || file.length() != update.metadata.size
                || !update.metadata.sha256.equals(wantHash)) return false;
        try {
            if (!VeloraApkSignature.verifyDigest(file, update.metadata.size, update.metadata.sha256)) return false;
            String signer = trustedSignerFingerprint(file);
            if (signer == null) return false;
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    private static String trustedSignerFingerprint(File file) throws Exception {
        Update update = getOrRestoreLastUpdate();
        if (update == null) return null;
        Context context = ApplicationLoader.applicationContext;
        android.content.pm.PackageManager pm = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28
                ? android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
                : android.content.pm.PackageManager.GET_SIGNATURES;
        android.content.pm.PackageInfo archive = pm.getPackageArchiveInfo(file.getAbsolutePath(), flags);
        android.content.pm.PackageInfo installed = pm.getPackageInfo(context.getPackageName(), flags);
        if (archive == null || archive.applicationInfo == null || installed == null
                || !VeloraRelease.PACKAGE_NAME.equals(context.getPackageName())
                || !update.metadata.acceptsApk(archive.packageName,
                        androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(archive),
                        archive.versionName, archive.applicationInfo.minSdkVersion,
                        androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(installed), Build.VERSION.SDK_INT)) return null;
        String pin = org.telegram.messenger.BuildConfig.VELORA_RELEASE_CERT_SHA256;
        android.content.pm.Signature[] installedSignatures = signaturesOf(installed);
        if (installedSignatures.length != 1 || !pin.equals(signatureSha256(installedSignatures[0]))) return null;
        return VeloraApkSignature.verify(file, pin, update.metadata.minSdk, Build.VERSION.SDK_INT) ? pin : null;
    }

    private static String signatureSha256(android.content.pm.Signature signature) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(signature.toByteArray());
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (byte b : digest) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }

    private static android.content.pm.Signature[] signaturesOf(android.content.pm.PackageInfo info) {
        if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
            android.content.pm.Signature[] signatures = info.signingInfo.getApkContentsSigners();
            return signatures == null ? new android.content.pm.Signature[0] : signatures;
        }
        return info.signatures == null ? new android.content.pm.Signature[0] : info.signatures;
    }










    public static String sha256OfFile(File file) {
        try (InputStream in = new java.io.FileInputStream(file)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 14];
            int read;
            while ((read = in.read(buf)) != -1) md.update(buf, 0, read);
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    public static String getCurrentVersionName() {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            FileLog.e(e);
            return "";
        }
    }

    public static int getCurrentVersionCode() {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            android.content.pm.PackageInfo pInfo = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return (int) androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pInfo);
        } catch (Exception e) {
            FileLog.e(e);
            return 0;
        }
    }
    public static File otaPath, versionPath, apkFile;

    public static long id = 1L;
    private static final long updateCheckInterval = 24L * 60 * 60 * 1000;
    private static volatile boolean updateDownloaded = false;
    private static volatile boolean checkingForUpdates = false;

    public static boolean isUpdateDownloaded() {
        return updateDownloaded && apkFile != null && apkFile.isFile();
    }

    public static boolean isStableUpdaterEnabled() {
        Context context = ApplicationLoader.applicationContext;
        return context != null && VeloraRelease.PACKAGE_NAME.equals(context.getPackageName())
                && org.telegram.messenger.BuildConfig.VELORA_RELEASE_CERT_SHA256.matches("[0-9a-f]{64}");
    }

    public static volatile boolean noPublishedRelease;

    private static void selectUpdate(Update update) {
        lastUpdate = update;
        version = update.version;
        versionCode = update.versionCode;
        expectedSha256 = update.metadata.sha256;
        expectedSizeBytes = update.metadata.size;
        downloadURL = update.downloadURL;
        changelog = update.changelog;
        size = update.size;
        uploadDate = update.uploadDate;
    }

    public static Update getOrRestoreLastUpdate() {
        if (!isStableUpdaterEnabled()) return null;
        Update update = lastUpdate;
        try {
            if (update == null) {
                String json = NimarkoUpdateConfig.getReleaseMetadata();
                if (json.isEmpty()) return null;
                update = new Update(VeloraRelease.parse(json,
                        org.telegram.messenger.BuildConfig.VELORA_RELEASE_CERT_SHA256),
                        NimarkoUpdateConfig.getLastUpdateChangelog(), NimarkoUpdateConfig.getLastUpdateDate());
            }
            if (!update.metadata.isNewerThan(getCurrentVersionCode(), Build.VERSION.SDK_INT)) return null;
            selectUpdate(update);
            return update;
        } catch (Exception e) {
            FileLog.e(e);
            return null; // Never restore the legacy Telegram-channel metadata.
        }
    }

    private static Runnable progressRunnable;

    public interface OnUpdateNotFound { void run(); }

    public static boolean checkDirs() {
        Update selected = getOrRestoreLastUpdate();
        if (selected == null) { updateDownloaded = false; return false; }
        try {
            otaPath = new File(ApplicationLoader.applicationContext.getCacheDir(), "velora-updates");
            versionPath = new File(otaPath, Integer.toString(selected.versionCode));
            if (!versionPath.exists() && !versionPath.mkdirs()) return false;
            apkFile = new File(versionPath, "update.apk");
            if (Looper.myLooper() == Looper.getMainLooper()) {
                if (apkFile.isFile()) otaQueue.postRunnable(NimarkoUpdater::checkDirs);
                return true;
            }
            updateDownloaded = isApkValid(apkFile);
            if (updateDownloaded) AndroidUtilities.runOnUIThread(() -> dispatchDownloadComplete(downloadGeneration));
            if (apkFile.exists() && !updateDownloaded && !downloading && !downloadPaused && !hasPersistedDownloadLocked()) apkFile.delete();
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            updateDownloaded = false;
            return false;
        }
    }

    public static void checkUpdates(BaseFragment fragment, boolean manual) {
        checkUpdates(fragment, manual, null, null, null);
    }


    public interface OnCheckFailed { void run(); }

    public static void checkOnLaunch(BaseFragment fragment) {
        if (NimarkoUpdateConfig.getAutoOTA()) checkUpdates(fragment, false);
    }

    public static void checkUpdates(BaseFragment fragment, boolean manual, OnUpdateNotFound onUpdateNotFound, Runnable onUpdateFound) {
        checkUpdates(fragment, manual, onUpdateNotFound, onUpdateFound, null);
    }

    public static void checkUpdates(BaseFragment fragment, boolean manual, OnUpdateNotFound onUpdateNotFound, Runnable onUpdateFound, OnCheckFailed onCheckFailed) {
        if (!isStableUpdaterEnabled()) {
            if (onCheckFailed != null) AndroidUtilities.runOnUIThread(onCheckFailed::run);
            return;
        }
        long elapsed = System.currentTimeMillis() - NimarkoUpdateConfig.getLastUpdateCheckTime();
        if (!manual && (!NimarkoUpdateConfig.getAutoOTA()
                || elapsed >= 0 && elapsed < updateCheckInterval)) return;
        synchronized (downloadBindingLock) {
            if (checkingForUpdates || downloading || downloadPaused || hasPersistedDownloadLocked()) {
                if (onCheckFailed != null) AndroidUtilities.runOnUIThread(onCheckFailed::run);
                return;
            }
            checkingForUpdates = true;
        }
        otaQueue.postRunnable(() -> {
            try {
                GitHubReleaseClient.Result result = GitHubReleaseClient.latest(
                        org.telegram.messenger.BuildConfig.VELORA_RELEASE_CERT_SHA256);
                noPublishedRelease = result == null;
                if (result == null || !result.metadata.isNewerThan(getCurrentVersionCode(), Build.VERSION.SDK_INT)) {
                    lastUpdate = null;
                    NimarkoUpdateConfig.setUpdateAvailable(false);
                    NimarkoUpdateConfig.setReleaseMetadata("");
                    if (onUpdateNotFound != null) AndroidUtilities.runOnUIThread(onUpdateNotFound::run);
                    return;
                }
                Update update = new Update(result.metadata, result.changelog, result.publishedAt);
                selectUpdate(update);
                NimarkoUpdateConfig.setReleaseMetadata(result.metadata.toJson());
                NimarkoUpdateConfig.setLastUpdate(update.version, update.versionCode, update.downloadURL,
                        update.changelog, update.size, update.uploadDate);
                NimarkoUpdateConfig.setApkSha256(result.metadata.sha256);
                NimarkoUpdateConfig.setUpdateAvailable(true);
                NimarkoUpdateConfig.setUpdateVersionName(update.version);
                checkDirs();
                AndroidUtilities.runOnUIThread(() -> {
                    if (fragment != null && fragment.getParentActivity() != null) {
                        NimarkoUpdaterSheet.showAlert(fragment, true, update);
                    }
                    if (onUpdateFound != null) onUpdateFound.run();
                });
            } catch (Exception e) {
                FileLog.e(e);
                if (onCheckFailed != null) AndroidUtilities.runOnUIThread(onCheckFailed::run);
                else if (manual && fragment != null) AndroidUtilities.runOnUIThread(() -> {
                    if (fragment.getParentActivity() != null) {
                        AlertsCreator.createSimpleAlert(fragment.getParentActivity(),
                                LocaleController.getString(R.string.UP_CheckFailed)).show();
                    }
                });
            } finally {
                NimarkoUpdateConfig.setLastUpdateCheckTime(System.currentTimeMillis());
                checkingForUpdates = false;
            }
        });
    }

    private static volatile boolean downloadCanceled = false;
    public static volatile Update lastUpdate;
    private static volatile boolean downloading = false;
    private static volatile boolean downloadPaused = false;
    private static volatile long pausedBytes = 0;
    private static volatile String downloadLink;
    private static volatile int dlRealProgress = 0;
    private static int dlShownProgress = 0;
    private static volatile HttpURLConnection activeConnection;
    private static volatile int downloadGeneration = 0;
    private static volatile int activeDownloadGeneration = -1;
    public interface DownloadUiOwner {
        void onDownloadComplete();
        void onDownloadError();
    }

    public static final class DownloadUiState {
        public final boolean downloading;
        public final boolean paused;
        public final boolean finished;
        public final int progress;

        private DownloadUiState(boolean downloading, boolean paused, boolean finished, int progress) {
            this.downloading = downloading;
            this.paused = paused;
            this.finished = finished;
            this.progress = progress;
        }
    }

    private static final class DownloadUiBinding {
        final long ownerToken;
        final int generation;
        final WeakReference<ButtonWithCounterView> button;
        final WeakReference<DownloadUiOwner> owner;

        DownloadUiBinding(long ownerToken, int generation, ButtonWithCounterView button,
                          DownloadUiOwner owner) {
            this.ownerToken = ownerToken;
            this.generation = generation;
            this.button = new WeakReference<>(button);
            this.owner = new WeakReference<>(owner);
        }
    }

    private static final Object downloadBindingLock = new Object();
    private static long nextBindingToken;
    private static DownloadUiBinding activeBinding;

    private static boolean hasPersistedDownloadLocked() {
        if (!NimarkoUpdateConfig.getUpdateIsDownloading()) return false;
        String link = NimarkoUpdateConfig.getPausedDownloadLink();
        String targetVersion = NimarkoUpdateConfig.getPausedDownloadVersion();
        return link != null && !link.isEmpty()
                && targetVersion != null && !targetVersion.isEmpty();
    }

    public static long bindDownloadUi(ButtonWithCounterView button, DownloadUiOwner owner) {
        if (button == null || owner == null) return 0L;
        final long token;
        final int generation;
        synchronized (downloadBindingLock) {
            token = ++nextBindingToken;
            generation = downloadGeneration;
            activeBinding = new DownloadUiBinding(token, generation, button, owner);
        }
        if (downloading) {
            startProgressSmoother(generation);
        }
        return token;
    }

    public static void unbindDownloadUi(long ownerToken) {
        synchronized (downloadBindingLock) {
            if (activeBinding != null && activeBinding.ownerToken == ownerToken) {
                activeBinding = null;
                stopProgressSmoother();
            }
        }
    }

    public static DownloadUiState getDownloadUiState() {
        synchronized (downloadBindingLock) {
            int progress = Math.max(dlShownProgress, dlRealProgress);
            if (progress <= 0) {
                progress = Math.max(0, Math.round(NimarkoUpdateConfig.getUpdateDownloadingProgress()));
            }
            boolean paused = downloadPaused || !downloading && hasPersistedDownloadLocked();
            return new DownloadUiState(downloading, paused, updateDownloaded,
                    Math.max(0, Math.min(100, progress)));
        }
    }

    public static void downloadApk(Context context, String link, String title, long ownerToken) {
        if (context == null) return;
        Update selected = getOrRestoreLastUpdate();
        if (selected == null || !selected.downloadURL.equals(link)) {
            dispatchDownloadError(downloadGeneration);
            return;
        }
        Context appContext = context.getApplicationContext();
        if (updateDownloaded && apkFile != null && apkFile.exists()) {
            installApk(context, apkFile.getAbsolutePath());
            return;
        }
        final int generation;
        synchronized (downloadBindingLock) {
            if (activeBinding == null || activeBinding.ownerToken != ownerToken
                    || checkingForUpdates || downloading || downloadPaused || hasPersistedDownloadLocked()) {
                dispatchDownloadError(downloadGeneration);
                return;
            }
            downloading = true;
            generation = ++downloadGeneration;
            activeDownloadGeneration = generation;
            ButtonWithCounterView button = activeBinding.button.get();
            DownloadUiOwner owner = activeBinding.owner.get();
            activeBinding = button != null && owner != null
                    ? new DownloadUiBinding(ownerToken, generation, button, owner) : null;
            if (activeBinding == null) {
                downloading = false;
                activeDownloadGeneration = -1;
                return;
            }
            if (apkFile != null && apkFile.exists()) apkFile.delete();
            updateDownloaded = false;
            downloadCanceled = false;
            downloadPaused = false;
            pausedBytes = 0;
            downloadLink = link;
            dlRealProgress = 0;
            dlShownProgress = 0;
            NimarkoUpdateConfig.setPausedDownloadLink(link);
            NimarkoUpdateConfig.setPausedDownloadVersion(version);
            NimarkoUpdateConfig.setPausedDownloadOffset(0);
            NimarkoUpdateConfig.setPausedDownloadSize(expectedSizeBytes);
            NimarkoUpdateConfig.setPausedDownloadSha256(expectedSha256);
            NimarkoUpdateConfig.setUpdateIsDownloading(true);
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateLoading);
        }
        AndroidUtilities.runOnUIThread(() -> {
            updateBoundButton(generation, 0, false);
            startProgressSmoother(generation);
        });
        nmDownloadLoop(appContext, link, 0, generation, false);
    }

    private static void nmDownloadLoop(Context context, String link, long startOffset,
                                       int myGeneration, boolean resume) {
        otaQueue.postRunnable(() -> {
            HttpURLConnection connection = null;
            try {
                if (myGeneration != downloadGeneration) return;
                File baseDir = new File(context.getCacheDir(), "velora-updates/" + versionCode);
                if (!baseDir.exists() && !baseDir.mkdirs() && !baseDir.exists()) {
                    throw new IllegalStateException("Cannot create dir: " + baseDir.getAbsolutePath());
                }
                File outFile = new File(baseDir, "update.apk");

                lastNotifProgress = -1;
                long offset = resume && outFile.exists() ? outFile.length() : startOffset;
                if (offset > 0 && (!outFile.exists()
                        || expectedSizeBytes > 0 && offset >= expectedSizeBytes)) {
                    offset = 0;
                }
                connection = openApkConnection(link, offset);
                if (myGeneration != downloadGeneration) return;
                activeConnection = connection;
                final boolean append = offset > 0 && connection.getResponseCode() == 206;
                if (!append) offset = 0;
                long total = connection.getContentLengthLong();
                if (append && total > 0) total += offset;
                long expectedTotal = expectedSizeBytes > 0 ? expectedSizeBytes : total;
                if (expectedTotal > MAX_APK_BYTES) {
                    throw new java.io.IOException("invalid download length: " + expectedTotal);
                }
                showProgressNotification(context, expectedTotal > 0 ? (int) (offset * 100L / expectedTotal) : 0);

                long downloaded = offset;
                byte[] buf = new byte[1 << 14];
                try (InputStream in = connection.getInputStream();
                     FileOutputStream out = new FileOutputStream(outFile, append)) {
                    int read;
                    while ((read = in.read(buf)) != -1) {
                        if (downloadCanceled) {
                            outFile.delete();
                            return;
                        }
                        if (downloadPaused) {
                            pausedBytes = downloaded;
                            out.flush();
                            out.getFD().sync();
                            NimarkoUpdateConfig.setPausedDownloadOffset(downloaded);
                            showPausedNotification(context, expectedTotal > 0 ? (int) (downloaded * 100L / expectedTotal) : 0);
                            return;
                        }
                        out.write(buf, 0, read);
                        downloaded += read;
                        if ((expectedTotal > 0 && downloaded > expectedTotal) || downloaded > MAX_APK_BYTES) {
                            throw new java.io.IOException("download exceeded expected length");
                        }
                        if (expectedTotal > 0) {
                            dlRealProgress = (int) (downloaded * 100L / expectedTotal);
                            NimarkoUpdateConfig.setUpdateDownloadingProgress(dlRealProgress);
                            NimarkoUpdateConfig.setPausedDownloadOffset(downloaded);
                            if (dlRealProgress != lastNotifProgress && (dlRealProgress % 5 == 0 || dlRealProgress >= 99)) {
                                lastNotifProgress = dlRealProgress;
                                showProgressNotification(context, dlRealProgress);
                            }
                        }
                    }
                    out.flush();
                    out.getFD().sync();
                }

                if (expectedTotal > 0 && downloaded != expectedTotal) {
                    throw new java.io.IOException("incomplete download " + downloaded + "/" + expectedTotal);
                }

                boolean hashVerified = false;
                if (expectedSha256 != null && SHA256_PATTERN.matcher(expectedSha256).matches()) {
                    String got = sha256OfFile(outFile);
                    if (got == null || !got.equalsIgnoreCase(expectedSha256)) {
                        outFile.delete();
                        throw new java.io.IOException("sha256 mismatch: expected="
                                + expectedSha256 + " got=" + got);
                    }
                    hashVerified = true;
                    NimarkoUpdateConfig.setApkSha256(expectedSha256);
                } else {
                    throw new java.io.IOException("Missing mandatory release digest");
                }

                boolean sizeVerified = (expectedSizeBytes > 0 && outFile.length() == expectedSizeBytes)
                        || (expectedTotal > 0 && downloaded == expectedTotal);
                if (!hashVerified && !sizeVerified) {
                    outFile.delete();
                    throw new java.io.IOException("unverifiable download: no sha256 and size unknown/mismatch");
                }

                if (myGeneration != downloadGeneration) {
                    return;
                }

                String warmHash = wantedApkHash();
                if (!validateApkFully(outFile, warmHash)) {
                    outFile.delete();
                    throw new java.io.IOException("APK package/signature validation failed");
                }

                synchronized (downloadBindingLock) {
                    if (myGeneration != downloadGeneration || downloadCanceled || downloadPaused) {
                        return;
                    }
                    dlRealProgress = 100;
                    apkFile = outFile;
                    versionPath = baseDir;
                    updateDownloaded = true;
                    downloading = false;
                    activeDownloadGeneration = -1;
                    NimarkoUpdateConfig.setUpdateIsDownloading(false);
                    NimarkoUpdateConfig.clearPausedDownload();
                }
                showReadyNotification(context, outFile);
                AndroidUtilities.runOnUIThread(() -> {
                    if (myGeneration != downloadGeneration || !updateDownloaded) return;
                    stopProgressSmoother();
                    updateBoundButton(myGeneration, 100, false);
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
                    dispatchDownloadComplete(myGeneration);
                });
            } catch (Exception e) {
                FileLog.e(e);
                if (downloadCanceled || downloadPaused || myGeneration != downloadGeneration) {
                    if (downloadPaused && !downloadCanceled && myGeneration == downloadGeneration) {
                        try {
                            File baseDir = new File(context.getCacheDir(), "velora-updates/" + versionCode);
                            File outFile = new File(baseDir, "update.apk");
                            long onDisk = outFile.exists() ? outFile.length() : 0;
                            pausedBytes = onDisk;
                            NimarkoUpdateConfig.setPausedDownloadOffset(onDisk);
                            long expected = expectedSizeBytes;
                            showPausedNotification(context, expected > 0 ? (int) (onDisk * 100L / expected) : 0);
                        } catch (Throwable ignore) {}
                    }
                    return;
                }
                synchronized (downloadBindingLock) {
                    if (myGeneration != downloadGeneration || downloadCanceled || downloadPaused) {
                        return;
                    }
                    updateDownloaded = false;
                    downloading = false;
                    activeDownloadGeneration = -1;
                    NimarkoUpdateConfig.setUpdateIsDownloading(false);
                    NimarkoUpdateConfig.clearPausedDownload();
                    cancelUpdateNotification();
                }
                AndroidUtilities.runOnUIThread(() -> {
                    if (myGeneration != downloadGeneration || downloading || downloadPaused) return;
                    stopProgressSmoother();
                    updateBoundButtonError(myGeneration);
                    dispatchDownloadError(myGeneration);
                });
            } finally {
                synchronized (downloadBindingLock) {
                    if (activeDownloadGeneration == myGeneration) {
                        downloading = false;
                        activeDownloadGeneration = -1;
                    }
                }
                if (activeConnection == connection) activeConnection = null;
                if (connection != null) connection.disconnect();
            }
        }, 50);
    }





    private static HttpURLConnection openApkConnection(String link, long rangeStart) throws Exception {
        Update update = getOrRestoreLastUpdate();
        if (update == null || !update.downloadURL.equals(link)) throw new java.io.IOException("Not the selected GitHub release");
        return GitHubReleaseClient.openDownload(link, rangeStart);
    }

    private static void startProgressSmoother(int generation) {
        if (generation != downloadGeneration || boundButton(generation) == null) return;
        stopProgressSmoother();
        if (generation != downloadGeneration || boundButton(generation) == null) return;
        progressRunnable = new Runnable() {
            @Override
            public void run() {
                if (downloadCanceled || generation != downloadGeneration
                        || boundButton(generation) == null) return;
                if (dlShownProgress < dlRealProgress) {
                    dlShownProgress += Math.max(1, (dlRealProgress - dlShownProgress) / 6);
                    if (dlShownProgress > dlRealProgress) dlShownProgress = dlRealProgress;
                    updateBoundButton(generation, dlShownProgress, false);
                }
                AndroidUtilities.runOnUIThread(this, 16);
            }
        };
        AndroidUtilities.runOnUIThread(progressRunnable, 16);
    }

    private static void stopProgressSmoother() {
        if (progressRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(progressRunnable);
            progressRunnable = null;
        }
    }

    private static ButtonWithCounterView boundButton(int generation) {
        synchronized (downloadBindingLock) {
            if (activeBinding == null || activeBinding.generation != generation) return null;
            return activeBinding.button.get();
        }
    }

    private static DownloadUiOwner boundOwner(int generation) {
        synchronized (downloadBindingLock) {
            if (activeBinding == null || activeBinding.generation != generation) return null;
            return activeBinding.owner.get();
        }
    }

    private static void updateBoundButton(int generation, int progress, boolean animated) {
        ButtonWithCounterView button = boundButton(generation);
        if (button != null) {
            button.setText(LocaleController.formatString(R.string.AppUpdateDownloading, progress), animated);
        }
    }

    private static void updateBoundButtonError(int generation) {
        ButtonWithCounterView button = boundButton(generation);
        if (button != null) {
            button.setText(LocaleController.getString(R.string.UP_DownloadFailed), true);
        }
    }

    private static void dispatchDownloadComplete(int generation) {
        DownloadUiOwner owner = boundOwner(generation);
        if (owner != null) owner.onDownloadComplete();
    }

    private static void dispatchDownloadError(int generation) {
        DownloadUiOwner owner = boundOwner(generation);
        if (owner != null) owner.onDownloadError();
    }


    private static final int UPDATE_NOTIF_ID = 0x4E47;
    private static final String UPDATE_CHANNEL = "nimarko_updates";
    private static int lastNotifProgress = -1;

    private static void ensureUpdateChannel(NotificationManager nm) {
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(UPDATE_CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(UPDATE_CHANNEL,
                    LocaleController.getString(R.string.NM_UpdateReady), NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
    }

    private static void showProgressNotification(Context context, int progress) {
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            ensureUpdateChannel(nm);
            NotificationCompat.Builder b = new NotificationCompat.Builder(context, UPDATE_CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle(LocaleController.getString(R.string.NM_UpdateDownloading))
                    .setProgress(100, Math.max(0, progress), progress <= 0)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setContentIntent(openUpdateScreenIntent(context))
                    .addAction(0, LocaleController.getString(R.string.NM_UpdatePause), pauseResumeIntent(context, true))
                    .addAction(0, LocaleController.getString(R.string.Cancel), cancelDownloadIntent(context));
            if (nm != null) nm.notify(UPDATE_NOTIF_ID, b.build());
        } catch (Throwable ignore) {}
    }

    private static void showPausedNotification(Context context, int progress) {
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            ensureUpdateChannel(nm);
            NotificationCompat.Builder b = new NotificationCompat.Builder(context, UPDATE_CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle(LocaleController.getString(R.string.NM_UpdatePaused))
                    .setProgress(100, Math.max(0, progress), false)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setContentIntent(openUpdateScreenIntent(context))
                    .addAction(0, LocaleController.getString(R.string.NM_UpdateResume), pauseResumeIntent(context, false))
                    .addAction(0, LocaleController.getString(R.string.Cancel), cancelDownloadIntent(context));
            if (nm != null) nm.notify(UPDATE_NOTIF_ID, b.build());
        } catch (Throwable ignore) {}
    }

    private static PendingIntent pauseResumeIntent(Context context, boolean pause) {
        Intent i = new Intent(context, NimarkoUpdaterReceiver.class);
        i.setAction(pause ? NimarkoUpdaterReceiver.ACTION_PAUSE : NimarkoUpdaterReceiver.ACTION_RESUME);
        return PendingIntent.getBroadcast(context, pause ? 4 : 5, i, nmPiFlags());
    }

    private static int nmPiFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
    }

    private static PendingIntent openUpdateScreenIntent(Context context) {
        Intent open = new Intent(context, org.telegram.ui.LaunchActivity.class);
        open.setAction("app.nimarkogram.messenger.OPEN_UPDATE");
        open.putExtra("nm_open_update", true);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, 2, open, nmPiFlags());
    }

    private static PendingIntent cancelDownloadIntent(Context context) {
        Intent cancel = new Intent(context, NimarkoUpdaterReceiver.class);
        cancel.setAction(NimarkoUpdaterReceiver.ACTION_CANCEL);
        return PendingIntent.getBroadcast(context, 3, cancel, nmPiFlags());
    }

    private static void showReadyNotification(Context context, File apk) {
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            ensureUpdateChannel(nm);
            NotificationCompat.Builder b = new NotificationCompat.Builder(context, UPDATE_CHANNEL)
                    .setSmallIcon(R.drawable.notification)
                    .setContentTitle(LocaleController.getString(R.string.NM_UpdateReady))
                    .setContentText(LocaleController.formatString(R.string.NM_UpdateReadyText, version == null ? "" : version))
                    .setAutoCancel(true)
                    .setContentIntent(openUpdateScreenIntent(context))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT);
            if (nm != null) nm.notify(UPDATE_NOTIF_ID, b.build());
        } catch (Throwable ignore) {}
    }

    private static void cancelUpdateNotification() {
        try {
            NotificationManager nm = (NotificationManager) ApplicationLoader.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(UPDATE_NOTIF_ID);
        } catch (Throwable ignore) {}
    }

    public static void installApk(Context context, String path) {
        if (context == null || path == null) return;
        File file = new File(path);
        Update selected = getOrRestoreLastUpdate();
        File expected = selected == null ? null : new File(new File(
                ApplicationLoader.applicationContext.getCacheDir(), "velora-updates/" + selected.versionCode), "update.apk");
        if (expected == null || !expected.equals(file) || !file.isFile()) return;
        Context appContext = ApplicationLoader.applicationContext;
        WeakReference<Activity> activityRef = new WeakReference<>(AndroidUtilities.findActivity(context));
        otaQueue.postRunnable(() -> {
            boolean valid = validateApkFully(file, selected.metadata.sha256);
            AndroidUtilities.runOnUIThread(() -> {
                if (valid) launchInstaller(appContext, activityRef, file);
                else {
                    rejectInvalidApk(file);
                    Activity activity = resolveInstallerActivity(activityRef);
                    if (activity != null) AlertsCreator.createSimpleAlert(activity,
                            LocaleController.getString(R.string.VeloraUpdateRejected)).show();
                }
            });
        });
    }

    private static void rejectInvalidApk(File file) {
        if (file != null) file.delete();
        updateDownloaded = false;
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
    }

    private static boolean canShowInstallerDialog(Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed()) {
            return false;
        }
        return activity.getWindow() != null
                && activity.getWindow().getDecorView() != null
                && activity.getWindow().getDecorView().isAttachedToWindow()
                && activity.getWindow().getDecorView().getWindowToken() != null;
    }

    private static Activity resolveInstallerActivity(WeakReference<Activity> activityRef) {
        Activity activity = activityRef == null ? null : activityRef.get();
        if (canShowInstallerDialog(activity)) {
            return activity;
        }
        activity = AndroidUtilities.getActivity();
        return canShowInstallerDialog(activity) ? activity : null;
    }

    private static void launchInstaller(Context context, WeakReference<Activity> activityRef, File file) {
        if (context == null || file == null || !file.exists()) return;
        Intent install = new Intent(Intent.ACTION_VIEW);
        Uri fileUri;
        if (Build.VERSION.SDK_INT >= 24) {
            fileUri = FileProvider.getUriForFile(context, context.getPackageName() + ".provider", file);
        } else {
            fileUri = Uri.fromFile(file);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ApplicationLoader.applicationContext.getPackageManager().canRequestPackageInstalls()) {
            Activity activity = resolveInstallerActivity(activityRef);
            if (activity == null) {
                showReadyNotification(context, file);
                return;
            }
            try {
                AlertsCreator.createApkRestrictedDialog(activity, null).show();
            } catch (android.view.WindowManager.BadTokenException e) {
                FileLog.e(e);
                showReadyNotification(context, file);
            }
            return;
        }
        if (fileUri != null) {
            install.setDataAndType(fileUri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            java.util.List<android.content.pm.ResolveInfo> installers = context.getPackageManager()
                    .queryIntentActivities(install, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
                            | android.content.pm.PackageManager.MATCH_SYSTEM_ONLY);
            if (!installers.isEmpty()) {
                android.content.pm.ActivityInfo target = installers.get(0).activityInfo;
                install.setComponent(new android.content.ComponentName(target.packageName, target.name));
                try {
                    context.startActivity(install);
                    cancelUpdateNotification();
                    return;
                } catch (android.content.ActivityNotFoundException | SecurityException e) {
                    FileLog.e(e);
                }
            }
            Activity activity = resolveInstallerActivity(activityRef);
            if (activity != null) AlertsCreator.createSimpleAlert(activity,
                    LocaleController.getString(R.string.VeloraInstallerUnavailable)).show();
        }
    }

    public static String getOtaDirSize() {
        File directory = new File(ApplicationLoader.applicationContext.getCacheDir(), "velora-updates");
        return AndroidUtilities.formatFileSize(Utilities.getDirSize(directory.getAbsolutePath(), 5, true), true, false);
    }

    public static void cleanOtaDir() {
        cancelDownload(ApplicationLoader.applicationContext, id);
        updateDownloaded = false;
        otaQueue.postRunnable(() -> cleanFolder(new File(
                ApplicationLoader.applicationContext.getCacheDir(), "velora-updates")));
    }

    public static void cleanFolder(File folder) {
        if (folder == null) return;
        if (folder.isDirectory()) {
            File[] files = folder.listFiles();
            if (files != null) for (File f : files) cleanFolder(f);
        }
        try { folder.delete(); } catch (Exception e) { FileLog.e(e); }
    }

    public static void cancelDownload(Context context, long downloadId) {
        final int canceledGeneration;
        final HttpURLConnection c;
        synchronized (downloadBindingLock) {
            if (!downloading && !downloadPaused && !hasPersistedDownloadLocked()) return;
            canceledGeneration = downloadGeneration;
            downloadCanceled = true;
            downloadPaused = false;
            pausedBytes = 0;
            downloadGeneration++;
            c = activeConnection;
            cancelUpdateNotification();
            NimarkoUpdateConfig.setUpdateIsDownloading(false);
            NimarkoUpdateConfig.setUpdateDownloadingProgress(0f);
            NimarkoUpdateConfig.clearPausedDownload();
        }
        if (c != null) {
            try { c.disconnect(); } catch (Throwable ignore) {}
        }
        stopProgressSmoother();
        File canceledFile = apkFile;
        otaQueue.postRunnable(() -> { if (canceledFile != null) canceledFile.delete(); });
        AndroidUtilities.runOnUIThread(() -> {
            updateBoundButtonError(canceledGeneration);
            dispatchDownloadError(canceledGeneration);
        });
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
    }

    public static void pauseDownload() {
        final HttpURLConnection c;
        synchronized (downloadBindingLock) {
            if (!downloading || downloadPaused) return;
            downloadPaused = true;
            c = activeConnection;
        }
        if (c != null) {
            try { c.disconnect(); } catch (Throwable ignore) {}
        }
        stopProgressSmoother();
    }

    public static void resumeDownload(Context context) {
        Update selected = getOrRestoreLastUpdate();
        if (selected == null || !selected.downloadURL.equals(NimarkoUpdateConfig.getPausedDownloadLink())
                || !selected.version.equals(NimarkoUpdateConfig.getPausedDownloadVersion())
                || selected.metadata.size != NimarkoUpdateConfig.getPausedDownloadSize()
                || !selected.metadata.sha256.equals(NimarkoUpdateConfig.getPausedDownloadSha256())) {
            cancelDownload(context, id);
            return;
        }
        if (context == null) return;
        final int resumedGeneration;
        final long resumeFrom;
        final String resumedLink;
        synchronized (downloadBindingLock) {
            if (downloadLink == null || (version == null || version.isEmpty())) {
                String savedLink = NimarkoUpdateConfig.getPausedDownloadLink();
                String savedVersion = NimarkoUpdateConfig.getPausedDownloadVersion();
                if (savedLink != null && !savedLink.isEmpty()) {
                    if (downloadLink == null) downloadLink = savedLink;
                    if ((version == null || version.isEmpty()) && savedVersion != null && !savedVersion.isEmpty()) {
                        version = savedVersion;
                    }
                    downloadPaused = true;
                }
            }
            if (expectedSizeBytes <= 0) {
                long savedSize = NimarkoUpdateConfig.getPausedDownloadSize();
                if (savedSize > 0) expectedSizeBytes = savedSize;
            }
            if (expectedSha256 == null || expectedSha256.isEmpty()) {
                String savedHash = NimarkoUpdateConfig.getPausedDownloadSha256();
                if (savedHash != null && !savedHash.isEmpty()) expectedSha256 = savedHash;
            }
            if (downloadLink == null || version == null || version.isEmpty() || !downloadPaused) return;

            long candidate = pausedBytes;
            try {
                File partial = new File(new File(context.getCacheDir(), "velora-updates/" + versionCode), "update.apk");
                long onDisk = partial.exists() ? partial.length() : 0;
                if (onDisk < candidate || candidate <= 0) candidate = onDisk;
                pausedBytes = candidate;
            } catch (Exception e) {
                FileLog.e(e);
            }

            downloadPaused = false;
            downloading = true;
            resumedGeneration = ++downloadGeneration;
            activeDownloadGeneration = resumedGeneration;
            downloadCanceled = false;
            if (activeBinding != null) {
                ButtonWithCounterView button = activeBinding.button.get();
                DownloadUiOwner owner = activeBinding.owner.get();
                activeBinding = button != null && owner != null
                        ? new DownloadUiBinding(activeBinding.ownerToken, resumedGeneration, button, owner)
                        : null;
            }
            resumeFrom = pausedBytes;
            resumedLink = downloadLink;
            NimarkoUpdateConfig.setUpdateIsDownloading(true);
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateLoading);
        }
        AndroidUtilities.runOnUIThread(() -> startProgressSmoother(resumedGeneration));
        nmDownloadLoop(context.getApplicationContext(), resumedLink, resumeFrom,
                resumedGeneration, true);
    }

    public static final class Update {
        public final String version, size, downloadURL, uploadDate, changelog;
        public final int versionCode;
        public final VeloraRelease metadata;

        Update(VeloraRelease metadata, String changelog, String uploadDate) {
            this.metadata = metadata;
            this.version = metadata.versionName;
            this.versionCode = metadata.versionCode;
            this.size = AndroidUtilities.formatFileSize(metadata.size);
            this.downloadURL = metadata.downloadUrl();
            this.uploadDate = uploadDate;
            this.changelog = changelog;
        }

        public boolean isNew() {
            return metadata.isNewerThan(getCurrentVersionCode(), Build.VERSION.SDK_INT);
        }
    }
}
