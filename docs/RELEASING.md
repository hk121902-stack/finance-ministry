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
