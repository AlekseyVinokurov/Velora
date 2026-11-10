# Velora 0.1.2: FCM configuration and acceptance

## Confirmed source findings

The 0.1.1 release workflow did not inject `google-services.json`. The standalone
module applied Google Services only when that file existed. No tracked config
or fallback Firebase string resources were found in the app/library resource
directories. The default `FirebaseApp.initializeApp(context)` needs those
resources; it can return null, after which `FirebaseMessaging.getInstance()`
fails. The old catch only logged the exception without setting a failure state.
Signing does not supply Firebase options. This confirms a broken configuration
path in the workflow, not inspection of the already installed APK or device.

The incoming path is `GcmPushListenerService` -> encrypted `data.p` ->
`PushListenerController` -> `NotificationsController.processNewMessages`.
Token type 2 is passed through `MessagesController.registerForPush` to
`account.registerDevice`, including the existing push encryption secret.
Only `TL_boolTrue` marks registration successful. Previously RPC failures had
no diagnostic result. Existing retries occur on startup/token refresh,
`getDifference`, and the existing three-hour registration check while the
application is running. No new retry timer, polling, or network loop was added.
Firebase SDK auto-initialization/token maintenance remains enabled; this patch
does not promise that every failed explicit token request immediately retries.
The Android >=35 resident-service restriction is unchanged.

## Owner action: provision one matching Firebase/Telegram push configuration

Provisioning completed according to the owner: the stable Firebase Android app
is registered, FCM HTTP v1 is enabled, Telegram accepted the project's service
key for the existing API application, and the environment client-config secret
is set. This confirms setup, not actual token acquisition or push delivery.
The steps below document the configuration contract for future maintenance.

1. In a Firebase project you control, register Android package
   `io.github.alekseyvinokurov.velora`. Download its Android **client**
   `google-services.json`. It must contain project ID, project number (sender
   ID), `mobilesdk_app_id`, and an API key for that project. Do not substitute a
   service-account JSON or an FCM server key. A DEV client with `.dev` is not
   sufficient. SHA fingerprints do not replace these options; if Android API
   key restrictions are configured, authorize the existing release package and
   certificate, without generating another signing key. Leave the API key
   usable by Firebase Installations/FCM registration APIs.
2. For the application corresponding to the existing environment secret
   `TELEGRAM_API_ID`, verify the push sender configuration in
   [Telegram application settings](https://my.telegram.org/apps). Telegram
   requires a GCM application key belonging to the project issuing the tokens.
   The Android `google_api_key` is **not** that server credential. Telegram's
   public documentation does not explain how a new project's HTTP v1 sender
   credentials are configured there. If the available settings cannot accept
   a working sender for this project, resolve that with Telegram before the
   final build. Do not invent a legacy key, paste a service-account key into
   an undocumented field, or assume that a token/`registerDevice` success
   proves Telegram can send. Server credentials never belong in this repo,
   Android APK, or this workflow.
3. In GitHub repository **Settings -> Environments -> velora-release**, allow
   deployments only from branch `main` (no tags) and enable required reviewer
   protection where available. Add environment secret
   `VELORA_GOOGLE_SERVICES_JSON_BASE64` containing base64 of the downloaded
   client JSON. Encode without line wrapping (for example `base64 -w0` on
   Linux) and paste directly into GitHub's secret form, not chat or a commit.
   Keep the existing six signing/Telegram secrets unchanged.

The workflow is manual, main-only and GitHub-hosted, uses no build cache, and
does not publish a GitHub Release. It fails before Android toolchain setup
if the Firebase secret is missing/invalid. It installs an ignored, mode-0600
client config; the existing clean-source checks remain intact. Signed Gradle
variants also fail if the module config is absent. No external credentials
were created or provided as part of this patch. Firebase client identifiers
are necessarily packaged in the APK and are not server authorization secrets.

## One final build, then Pixel 8 acceptance

After provisioning the matching sender and secret, commit/push the reviewed
changes to `main` and run **Android release APK** once on a GitHub-hosted
runner. Version is `0.1.2 (100002)`; no local/remote `v0.1.2` tag existed when
the patch was prepared. The existing build script verifies the permanent
signature, package, version, and digest. A further gate reads the signed APK
with SDK `apkanalyzer`, compares four Firebase resources to the client config,
and verifies FirebaseInitProvider and the non-exported FCM listener. The job
summary records a successful check without resource values. This verifies
packaging, not authorization or delivery by Firebase/Telegram.

On Pixel 8, install the signed update, launch it online, then check push
diagnostics with the existing application logging enabled only for acceptance:

- `firebase: initialized`, `token: ready`, and
  `registration_<account slot>: registered` must occur. A fixed failure state
  or numeric `rpc_error_<code>` identifies the failing stage without echoing
  the RPC error text. After restart, unchanged states are not logged again.
- Send a Telegram message from another device while Velora is minimized,
  first with the screen on, then off. Verify the visible notification and
  `receive: received` / `processing: notification_dispatched` transitions.
  Also test after Android reclaims the process, without force-stopping it.
  Repeat delivery after switching between Wi-Fi and LTE.
  The dispatch state means handoff to NotificationsController, not proof of
  presentation by Android. Notification behavior is verified on the device.
- Latest stage states and wall-clock timestamps are stored privately in
  `push_diagnostics.xml`; release app data is sandboxed and is not readable
  through ordinary `adb run-as`. Logs contain only state transitions and
  numeric RPC failures. The changed push handlers no longer log FCM tokens,
  encrypted payloads, push encryption keys, or decrypted JSON/exception text.
  Existing unrelated application logs were outside this targeted review.
- A Firebase console test alone does not verify Telegram sending. A real
  Telegram message received in the background is the final delivery test.

The final CI run verifies the signed artifact. Token acquisition, RPC
registration and real background delivery still require Pixel 8 acceptance.

References:

- [Firebase initialization requirements](https://firebase.google.com/support/guides/init-options)
- [Google Services resource generation](https://firebase.google.com/docs/android/google-services-plugin-and-file)
- [Telegram push configuration and registration](https://core.telegram.org/api/push-updates)
- [FCM HTTP v1 server authorization](https://firebase.google.com/docs/cloud-messaging/send/v1-api)
- [APK inspection commands](https://developer.android.com/tools/apkanalyzer)
