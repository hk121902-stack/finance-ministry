# Releasing debug alphas

The alpha channel distributes **debuggable** APKs from GitHub Releases. It is separate
from a future general-use/production distribution decision. Release names are
`vMAJOR.MINOR.PATCH-alpha.N`; the Android version name omits the leading `v`.

## One-time signing setup

Official alpha APKs must keep the same signing identity so Android can update an
installed alpha. Repository Actions secrets hold:

- `ALPHA_KEYSTORE_BASE64`: base64 of the dedicated PKCS12 keystore.
- `ALPHA_STORE_PASSWORD`: the keystore and private-key password.

Alias: `finance-alpha`. Never commit either value, the keystore, or a developer's
default debug keystore. Keep an offline backup of the dedicated key and password;
GitHub does not let you download an Actions secret later. Losing this signing key
can prevent in-place updates. Restrict repository write access because trusted
release workflow code can access these secrets.

The release workflow passes `FM_ALPHA_KEYSTORE`, `FM_ALPHA_STORE_PASSWORD` and
`FM_REQUIRE_ALPHA_SIGNING=true` into Gradle. A normal local build uses the developer's
standard debug signer. Official release builds fail if the persistent signer is absent.

## Each release

1. Update `android-app/version.properties`: choose a new `versionName` and a
   `versionCode` greater than every previous downloadable APK. Never reuse an old code.
2. Update `CHANGELOG.md`, including known issues and any upgrade restrictions.
3. Review the source changes and stage only intended files. Run
   `python tools/verify_public_tree.py`; no private corpus or signing files may be tracked.
   Before staging, `python tools/verify_public_tree.py --worktree` also checks current
   changes and non-ignored new files. The default continues to audit the exact staged snapshot.
4. Run relevant JVM and emulator tests. Build and lint with
   `./gradlew testDebugUnitTest lintDebug assembleDebug` from `android-app`.
5. For schema changes, supply a non-destructive Room migration and test installing the
   next APK over the previous official version with sample records preserved. Never
   use a destructive fallback to conceal a migration failure.
6. Merge/push the reviewed source to `main` and wait for Android CI to pass.
7. In **Actions → Draft alpha release → Run workflow**, select `main`. On Windows,
   `pwsh -File tools/publish-alpha.ps1` performs the same dispatch.
8. Review the resulting **draft pre-release** and its source commit, APK, SHA256SUMS,
   signing certificate and notices. Add device-testing results and meaningful changes.
9. Publish the draft while leaving **Set as a pre-release** enabled. Do not overwrite
   an existing tag or replace a published APK; issue a new version for changes.

The workflow validates version format, rejects existing tags and non-increasing
codes, runs JVM tests/lint/build, verifies APK signing and creates a draft with all
assets. It runs only from `main` and is manually dispatched; pull-request CI cannot
use release secrets. Tag creation/publishing is handled by GitHub Releases.

If a workflow fails, inspect its logs before retrying. If a draft/tag already exists,
inspect that state and use a new version when necessary rather than force-moving it.
The separate CI artifact contains reports only, not an officially signed download.

## Emulator verification

Use a dedicated emulator, not a personal phone. The instrumentation suite temporarily
creates sample rows and grants SMS/notification permissions. It cleans its own rows;
Android permission grants can remain afterward.

Standard tests: `./gradlew connectedDebugAndroidTest`. One host-driven SMS test is
intentionally skipped in that command because it needs a coordinated emulator SMS.

To run that test, target the emulator and add
`-Pandroid.testInstrumentationRunnerArguments.class=in.financeministry.app.SmsBroadcastE2eTest`
and `-Pandroid.testInstrumentationRunnerArguments.runSmsE2e=true`. While it runs,
wait until `adb -s emulator-5554 shell run-as in.financeministry.app test -f files/synthetic_sms_test_ready`
returns exit code 0, then send exactly one synthetic message:

```sh
adb -s emulator-5554 emu sms send 5551234 "INR 314.15 debited from your account via UPI"
```

The test has a 45-second receive window and checks receiver → encrypted record →
notification. Ordinary service calls and JVM parsing do not prove that platform path.

## Downloads and future production builds

### Signed upgrade verification

Use a disposable emulator with no personal ledger and always select its explicit
ADB serial. Install the published previous APK first. Build the candidate and its
instrumentation APK with the same dedicated alpha signer; never install an ordinary
developer-signed build over an official alpha.

Install the matching test APK with `adb -s SERIAL install -t TEST_APK`, then run:

```sh
adb -s SERIAL shell am instrument -w -e class in.financeministry.app.OfficialUpgradeTest -e upgradeStage seed in.financeministry.app.test/androidx.test.runner.AndroidJUnitRunner
adb -s SERIAL install -r CANDIDATE_APK
adb -s SERIAL shell am instrument -w -e class in.financeministry.app.OfficialUpgradeTest -e upgradeStage verify in.financeministry.app.test/androidx.test.runner.AndroidJUnitRunner
```

The seed stage creates and edits one synthetic record using the previous app's API.
The verify stage checks the amount, correction history, migrated schema and reopened
encrypted storage. Require `OK (1 test)` from both stages; ADB exit status alone is
not sufficient. Do not uninstall between stages. Repeat verification after stopping
the process to exercise cold database reopening. This is not proof that Android will
deliver SMS to a force-stopped app; force-stop and ordinary background process death
have different platform semantics.

For alpha.2, this protocol passed over the published alpha.1 APK on an isolated
Android 16 emulator, including cold process reopening. Physical-phone upgrades and
OEM-specific receiver behavior still need validation.

### Repository concurrency checks

Run `LedgerConcurrencyTest` and `LedgerIntegrationTest` on the isolated emulator
with the candidate's matching instrumentation APK. These tests use random database
namespaces and erase only their synthetic ledgers. The concurrency checks exercise
24 simultaneous duplicate deliveries, duplicates racing a correction, 12 concurrent
edits with a persisted audit chain, and erasure competing with an active capture
callback and 16 queued captures. They also verify that erased database keys stay
absent until a new explicit manual entry creates fresh storage.

Require `OK (14 tests)` for the combined classes. These are single-process repository
checks, not exhaustive thread scheduling, physical-device, or Android notification
cancellation tests. Do not run test suites against a personal phone ledger.

### Multipart timing and background process recovery

For a single-part automated system-SMS check, first build/install the app and
instrumentation APK on a disposable emulator. From the repository root run:

```powershell
pwsh -NoProfile -File tools/verify-sms-e2e.ps1 -AdbPath '<SDK>/platform-tools/adb.exe' -Serial emulator-5556 -Repetitions 3
```

The serial must name an emulator, not a physical phone. The driver waits for each
unique test-ready marker, injects synthetic INR 314.15 alerts, verifies sample count
and the explicit instrumentation `OK (1 test)` result, and preserves temporary logs.
It checks encrypted recording and active notification with the test's five-second
observer limit. It does not validate carrier delivery or authenticate bank senders.
CI now runs this after the default Android 16 instrumentation suite; other
host-dependent upgrade, inbox and process-restart checks still require their own
protocols below. Required alpha verification uses a normal debug signer, not the
production candidate or official alpha signing certificate.

On the same isolated emulator, run `SmsBroadcastE2eTest` with `runSmsE2e=true`,
`smsRepetitions=20`, and `expectMultipart=true`. The host must watch
`files/synthetic_sms_test_ready` using `run-as` and send exactly one synthetic SMS
per unique `UUID:sequence` marker. Use a unique sample label and enough neutral
padding to produce multiple segments, ending with the test's INR 314.15 debit.
Require `OK (1 test)` and inspect the delivered segment counts and timing output.
The test keeps the five-second assertion and reports all samples before applying it.

Timing is test-observer broadcast arrival to observing the encrypted record and
active notification, not exact production receiver entry or carrier-to-phone delay.
On Android 16, 20 three-segment samples passed at median 149 ms, p95 217 ms and max
1,830 ms with no concurrent build. An earlier run with a concurrent Gradle build
exceeded five seconds on sample three; host contention is a hypothesis, not a proven
root cause. Neither run establishes real-phone performance or the PRD latency gate.

For ordinary process recovery, run `ProcessRestartSmsTest` with `restartStage=prepare`
after the signed-upgrade seed exists. Launch the app, send it Home, then use
`adb -s SERIAL shell am kill in.financeministry.app`. Confirm `pidof` is empty before
sending `INR 701.23 credited via UPI; AvlBal: Rs9999.99` through the emulator console.
Observe the restarted process and notification before starting instrumentation again.
Run the same test with `restartStage=verify` and require `OK (1 test)`. It checks the
credit, notification and existing upgrade record, then deletes its synthetic credit
and restores preferences. Use only a disposable ledger. This protocol passed on the
isolated Android 16 emulator; it does not cover force-stop, reboot or OEM restrictions.

Users install the next official APK over the old one. The same package name, signer,
compatible schema and higher version code are necessary for an update. A development
build signed with a different key can conflict; do not tell users to uninstall
without explaining that their unbacked ledger will be deleted.

Before general use, verify real devices, upgrade paths, recovery/export strategy,
privacy behavior and a hardened non-debug build. A successful debug build alone
does not close those requirements.

### Production upload signing (not the debug-alpha release)

For a production candidate, set `FM_REQUIRE_RELEASE_SIGNING=true` and supply
`FM_RELEASE_KEYSTORE`, `FM_RELEASE_STORE_PASSWORD`, `FM_RELEASE_KEY_ALIAS` and
`FM_RELEASE_KEY_PASSWORD` securely in your build environment, then run
`./gradlew :app:bundleRelease`. Never commit keystores, credentials or generated
bundles. Release builds are non-debuggable, minified and resource-shrunk. Without
credentials, local release builds are unsigned candidates, not distributable releases.
The required-signing flag fails configuration when no upload keystore is provided.

The upload key and the Play app-signing key are not necessarily the same key.
Before publishing, decide the Play signing arrangement and verify certificate
compatibility with existing alpha installations. A different app-signing certificate
cannot update an existing installation: offer encrypted backup/recovery instructions,
never silently recommend uninstalling an unbacked ledger. Signed production artifact
validation, Play Console declarations/approval and physical-device testing remain
separate release gates; configuring signing does not close them.

## Play submission handoff — draft, not submitted

Reviewed 3 October 2026. The items below describe the current offline, free build.
Recheck them against the exact signed bundle and all active Play versions before
submission. Adding billing, analytics, cloud sync or a new SDK requires another review.

### Store listing copy

- App name: **Finance Ministry** (owner must approve the name and independent positioning).
- Suggested category: **Finance**.
- Short description: **Track spending from SMS, review transactions, and keep your ledger on device.**
- Website/source: https://github.com/hk121902-stack/finance-ministry
- Support and privacy contact: owner must supply a monitored address for Play Console;
  repository issues are not a substitute for required developer contact fields.
- Privacy-policy URL: publish the reviewed `docs/PRIVACY.md` content as a stable public
  HTML page; verify it loads without sign-in or geographic restrictions before entering
  its URL. A local file or unhosted draft does not satisfy this requirement.

Suggested full description:

> Finance Ministry is an independent, open-source personal expense tracker. Its core
> feature turns supported financial SMS alerts into an on-device ledger after you
> choose to enable SMS access. It is not a government service, bank or payment app.
>
> See monthly money in, money out and your own share of spending. Label payments as
> personal, family, for someone else or for a group, and track repayments separately.
> Review uncertain detections, edit records and add cash or missing transactions manually.
>
> Optionally preview and import financial messages still in your SMS inbox from the
> last three months. SMS access is optional; manual entry works without it. Notifications
> are a separate choice and offer quick categorization and editing.
>
> No account, ads or app backend. Create a password-encrypted backup or export an
> unencrypted CSV report to a location you choose. Keep the backup password safely;
> it cannot be recovered. Exported files may be synced by your chosen storage provider.
>
> Message formats and Android delivery vary. The app can miss or misclassify a
> transaction; review your ledger and do not use it as your only financial record.
> It does not initiate payments, connect to bank accounts or provide investment advice.

Do not advertise guaranteed accuracy, authenticated bank messages, real-time delivery,
government affiliation, regulatory approval or production certification.

### Restricted SMS declaration draft

Proposed exception: **SMS-based money management**, subject to Google's review.
This is not the SMS-based financial-transactions/payment-initiation use case.
Declare only `RECEIVE_SMS` and `READ_SMS`, both actually present in the bundle.

Suggested rationale:

> The core feature is an automated personal spending ledger created from supported
> financial SMS alerts. RECEIVE_SMS supports user-enabled new-alert recording;
> READ_SMS supports a separately consented, user-initiated last-three-month inbox
> import with preview, confirmation and undo. Processing and filtering happen on-device.
> Raw message bodies and sender strings are not retained in the ledger or uploaded.
> The app never sends SMS or initiates financial transactions. Users may decline
> permissions and use manual entry; that fallback does not reproduce automatic capture
> or historical financial-message import. Access is not used for advertising, profiling
> or unrelated messages.

Supply a fresh demo from the exact candidate showing disclosure before each prompt,
permission denial/manual fallback, a synthetic eligible alert, uncertain-message review,
historical import preview/confirmation/repeat deduplication, pause and erasure. Do not
submit private inbox footage. Ensure the listing prominently describes SMS-based
money management. Google's [permitted-use table](https://support.google.com/googleplay/android-developer/answer/10208820?hl=en)
includes this exception but does not grant automatic approval. If rejected, do not
work around the restriction with accessibility or notification scraping.

### Data Safety and App content draft

Current-build suggested answers, requiring owner review:

- Developer-controlled collection or sharing: **none identified**. Financial SMS,
  normalized ledger data and optional name/notes are processed locally. Review every
  bundled SDK and active distribution version before answering the Console form.
- User-chosen file exports: describe readable CSV and encrypted backups in the policy.
  Apply the user-initiated-transfer exception only where the chosen export destination
  and expected recipient satisfy it; do not claim files can never leave the device.
- No account creation: account-deletion flow is not applicable to this accountless build.
  In-app Erase all deletes local app data, not previously exported files or the SMS inbox.
- Encryption-in-transit: do not infer a **Yes** from SQLCipher or backup encryption.
  The app has no app-backend data transmission; answer the actual Console question
  consistently with any provider behavior and data flows.
- Ads and purchases/subscriptions: none in this build. Do not announce a trial or ₹30
  renewal while billing and entitlement enforcement are absent.
- App access: no login/test credentials; explain optional SMS permissions, manual entry
  and how reviewers can use synthetic samples without a bank account.
- Financial features: accurately declare expense/budget management using the Console's
  available choices. Do not declare lending, payment execution, trading or investment
  advice merely because an Investment category exists.
- Content rating, target audience and developer identity: complete honestly in Console;
  no rating or identity verification has been fabricated by this repository.

Sources: [Data Safety guidance](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en),
[User Data/privacy policy requirements](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en),
[Financial Services declaration](https://support.google.com/googleplay/android-developer/answer/9876821?hl=en).
On-device processing need not be declared as off-device collection, but it still needs
transparent permission and privacy disclosures.

### Owner-only gates before a production upload

1. Confirm the Play developer account, verified identity, contact details and intended
   countries; inspect the account's actual dashboard requirements.
2. Choose and securely back up the production/upload signing arrangement. Compare the
   Play app-signing certificate with the official alpha certificate before promising
   in-place upgrades. Keep the app-signing/upload-key distinction explicit.
3. Build the exact signed AAB with a new, increasing version code; verify its identity,
   signature and non-debuggable configuration. Validate the Play-generated install
   artifact, not just a separately signed local APK. Archive checksums and R8 mapping.
4. Complete physical Redmi/background capture, reinstall/recovery and representative
   device checks, including a 16 KB runtime. Static ELF/ZIP alignment alone is not runtime proof.
5. Publish and verify the public policy URL, review listing/screenshots and submit
   declarations. Production approval, not an exception rationale, is the gate.
6. For personal accounts created after 13 November 2023, the current requirement is a
   closed test with at least 12 testers continuously opted in for 14 days before applying
   for production access. Determine applicability from the actual account; emulator runs
   cannot substitute. See [official testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en).
7. Review the draft release and approve publication separately. Keep subscriptions
   deferred until the chosen model and full billing lifecycle are implemented and tested.

### Existing-alpha migration and rollback

If certificates match, test the *previous official APK* → exact new signed candidate
using the signed-upgrade protocol above, without uninstalling. Check amounts, corrections,
repayments, custom categories, sources and settings after cold restart. A transition
between locally test-signed debug/release builds is useful R8 validation, not this gate.

If certificates differ, Android cannot replace that installation in place. Before any
uninstall, create an encrypted backup in the old app, keep its password separately and
validate restoration on a disposable fresh installation. Notify users that uninstall
deletes their device ledger; CSV cannot restore it. The restored installation must
reconfirm capture and reminders and review historical import matching.

Stop rollout on data loss, incorrect totals, capture crashes or failed migration. Do
not overwrite published artifacts or promise an Android downgrade. Prefer a corrected
release with a higher version code; preserve backups before any manual recovery.
