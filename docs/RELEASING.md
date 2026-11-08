**English** · [Русский](RELEASING.ru.md)

# Releasing Velora

For cloud builds using the existing permanent key, follow [GITHUB_ACTIONS.md](GITHUB_ACTIONS.md).
That procedure supersedes the local-only CI restriction below. Do not create a new key.

This process stages and verifies artifacts locally. It does not push, create a tag remotely, create a GitHub Release or upload a key to CI. Actual completion is recorded in [VELORA_STATUS.md](VELORA_STATUS.md).

## 1. Create the single permanent signing identity

No real key or fingerprint was supplied during setup. In your own interactive terminal:

```bash
scripts/create-release-key.sh
```

The script generates RSA-4096 / SHA256withRSA in a PKCS#12 keystore, alias `velora-release`, with a 12,000-day certificate lifetime (over 30 years). The default path is `$HOME/.config/velora/signing/velora-release.p12`, outside Git; an alternative must also be outside every working tree. An existing key or permanent certificate pin is never overwritten. New key files have owner-only permissions.

`keytool` prompts for a unique long passphrase and your own certificate subject; no password, company, address or contact is invented. Use a password manager and do not record the terminal. Passwords are never command arguments or command-history entries. The helper exports a temporary **public** certificate, calculates its actual SHA-256, removes the temporary export and writes the public pin to `release/certificate.sha256` and [SIGNING_CERTIFICATE.md](SIGNING_CERTIFICATE.md).

Review and commit those two public files. The private keystore must never be committed, copied into the APK, placed in release staging or uploaded as a CI artifact. Do not put the permanent key in GitHub Actions. If creation was interrupted after the key was generated, do not generate a replacement; register the existing key interactively:

```bash
scripts/register-release-certificate.sh /absolute/external/path/velora-release.p12 velora-release
```

Registration refuses a fingerprint different from an already pinned identity. Gradle additionally checks the private alias, RSA size, certificate validity and pin before any stable build. The release key's original certificate validity must be at least 30 years. Ordinary releases never rotate the key or pin.

## 2. Back up before distributing an APK

Keep two encrypted copies of the **same** keystore on separate devices/locations, outside repositories and build artifacts. Store the passphrase separately in a password manager, along with the alias and keystore format. Record the public fingerprint.

Test a restore to a separate external directory: open the restored keystore interactively and export/check its public certificate fingerprint against the committed pin. Do not overwrite the original while testing recovery. Losing the private key/passphrase prevents issuing compatible direct-APK updates; creating a new key does not repair that. Do not advise users to uninstall the stable app to work around a signing error.

## 3. Fix the source and version

Prepare the environment and your own Telegram parameters using [BUILDING.md](BUILDING.md). Edit `gradle.properties` explicitly:

- `APP_VERSION_NAME`: semantic version, e.g. the planned `0.1.0`, followed by `0.1.1`.
- `APP_VERSION_CODE`: integer, currently planned `100000`, then `100001`, etc.; greater than every previously published APK.

No local Gradle run increments a version and no ABI number is appended. Each published version/tag names one immutable artifact set. Do not reuse a published version code, move its tag or replace its APK with a rebuild. Commit reviewed source and keep the tree clean. Preserve all submodule SHAs and matching native prebuilds.

## 4. Build and verify the real stable APK

```bash
scripts/build-stable.sh
```

Use your own external key. The wrapper requests passwords without echo, passes them only to a one-shot Gradle environment and disables configuration caching. The key itself stays external. It invokes:

```bash
./gradlew --no-daemon --no-configuration-cache :TMessagesProj_AppStandalone:assembleAfatStandalone
```

That raw command is useful only if your own signing parameters are already set locally; do not put passwords on its command line. Stable builds fail without complete `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` and a matching public pin.

The wrapper checks `app.apk` with `aapt2` / `apksigner`: stable package, exact fixed version, minSdk, verified APK signature, RSA size and the single pinned signer. It computes SHA-256 and rejects embedded keystore/private-configuration files. The receipt `.local-build/stable-build.json` records the actual APK and clean source commit. It refuses source changes during a build.

The release script's tests use disposable random fixture keys only. Their success is not evidence of a successful Velora APK build or a device upgrade.

## 5. Prepare the release artifacts locally

Write factual release notes covering the final changes, known issues and actual testing. After the source/notes are committed:

```bash
python3 scripts/release.py prepare \
  TMessagesProj_AppStandalone/build/outputs/apk/afat/standalone/app.apk \
  --notes /path/to/reviewed-release-notes.md
```

The script requires the matching build receipt and clean source commit, re-verifies the APK, and checks the public latest stable GitHub metadata. It refuses non-increasing version codes/names, a different published signer, malformed metadata and network/rate-limit errors. HTTP 404 means there is no public stable release to compare. If an older release has no metadata, inspect it manually and establish the correct version floor before proceeding; the script does not guess.

The new directory `.local-build/releases/v<versionName>/` contains only:

- `Velora-<versionName>-<versionCode>.apk` — the verified bytes, copied without rebuilding.
- `velora-release.json` — generated from that actual APK, public certificate and source commit.
- `SHA256SUMS` — digests of the staged APK, metadata, notes and source manifest.
- `SOURCE.json` — exact source commit and recorded submodule SHAs.
- `RELEASE_NOTES.md` — your reviewed notes.

An existing staging directory is not overwritten. No keystore, passwords, Telegram configuration or arbitrary build directory is copied.

## 6. Publish manually, only after approval

Publication was not performed by this task. After your own review/authorization:

1. Ensure the exact source commit and component sources/notices are available in the repository; keep corresponding GPL source available with the distributed build. `SOURCE.json` is an index, not a substitute for source availability.
2. In [GitHub Releases](https://github.com/AlekseyVinokurov/Velora/releases), prepare a **draft** targeting that exact commit with tag `v<versionName>` and the reviewed notes as its body.
3. Upload all five staged public files, keeping the metadata asset name exactly `velora-release.json` and the APK name unchanged. Verify the uploaded checksums.
4. Review again, then publish as a stable release: **not draft, not prerelease**, set as latest. Do not change these assets after publication.
5. Verify the public API and in-app update flow from a previous stable version. If the public API is rate limited, wait/retry; never interpret that failure as successful verification.

No automatic publisher is installed. Adding CI signing/publication later requires a separate design for protected secrets and explicit authorization; it must not happen implicitly as part of this setup.

## 7. Validate two consecutive builds on a device

**Not yet performed.** Once real Telegram parameters, the permanent key and a device are available, make build A and then build B from separately committed, increasing versions, using the same key/applicationId. These must be real application APKs, not host fixtures.

On the declared primary device, Google Pixel 8 / Android 17:

1. Install A; use an appropriate test account, record selected settings and local test data privately.
2. Verify both APKs' package, signer and increasing versionCode with `scripts/release.py verify` at each version's source commit.
3. Install B over A with Android's update flow. For a separate USB/ADB retention check, `adb install -r /path/to/B.apk` is acceptable. Do not use `-d`, uninstall or `pm clear`.
4. Confirm the account/session, settings, local test files and expected behavior remain intact; record both versions, certificate fingerprint, device and result without exposing personal data.
5. Separately test the published in-app path: no release, offline/rate-limit, progress, cancellation, retry/resume, unknown-source permission, rejected/tampered APK and Android confirmation. There is no claim of an end-to-end GitHub update until that test is actually completed.

Publishing is a separate manual step; do not publish a test release just to make a check appear complete. Testing on other devices remains open.
