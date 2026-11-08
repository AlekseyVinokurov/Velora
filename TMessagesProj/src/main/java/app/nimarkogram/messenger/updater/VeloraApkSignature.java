package app.nimarkogram.messenger.updater;

import com.android.apksig.ApkVerifier;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.List;

/** Uses Android's official APK verifier, rather than trusting a parsed certificate alone. */
public final class VeloraApkSignature {
    public static boolean verify(File apk, String trustedCertificate, int minSdk, int deviceSdk) throws Exception {
        if (trustedCertificate == null || !trustedCertificate.matches("[0-9a-f]{64}")) return false;
        ApkVerifier.Result result = new ApkVerifier.Builder(apk)
                .setMinCheckedPlatformVersion(minSdk)
                .setMaxCheckedPlatformVersion(deviceSdk)
                .build().verify();
        if (!result.isVerified() || !(result.isVerifiedUsingV2Scheme() || result.isVerifiedUsingV3Scheme())) return false;
        List<X509Certificate> signers = result.getSignerCertificates();
        return signers.size() == 1 && trustedCertificate.equals(fingerprint(signers.get(0).getEncoded()));
    }

    public static boolean verifyDigest(File apk, long expectedSize, String expectedSha256) throws Exception {
        if (apk == null || !apk.isFile() || expectedSize <= 0 || expectedSize > VeloraRelease.MAX_APK_BYTES
                || apk.length() != expectedSize || expectedSha256 == null
                || !expectedSha256.matches("[0-9a-f]{64}")) return false;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream stream = new FileInputStream(apk)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return expectedSha256.equals(hex(digest.digest()));
    }

    public static String fingerprint(byte[] certificate) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(certificate));
    }

    private static String hex(byte[] digest) {
        char[] hex = "0123456789abcdef".toCharArray();
        StringBuilder out = new StringBuilder(64);
        for (byte value : digest) out.append(hex[(value >>> 4) & 15]).append(hex[value & 15]);
        return out.toString();
    }

    private VeloraApkSignature() {}
}
