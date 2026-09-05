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

Users install the next official APK over the old one. The same package name, signer,
compatible schema and higher version code are necessary for an update. A development
build signed with a different key can conflict; do not tell users to uninstall
without explaining that their unbacked ledger will be deleted.

Before general use, verify real devices, upgrade paths, recovery/export strategy,
privacy behavior and a hardened non-debug build. A successful debug build alone
does not close those requirements.
