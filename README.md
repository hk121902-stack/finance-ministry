# Finance Ministry

An Android expense tracker that records transactions from incoming SMS and lets you add or correct them yourself.

[Download alpha APKs](https://github.com/hk121902-stack/finance-ministry/releases) · [Roadmap](docs/ROADMAP.md) · [Privacy](docs/PRIVACY.md) · [Report a bug](https://github.com/hk121902-stack/finance-ministry/issues/new/choose)

**Status: early alpha.** The release channel is for installable **debug APKs** for testing. The app is being developed by one maintainer and is not ready for general use. Parsing can miss or misclassify transactions; check your records. Do not make this your only financial record.

The current debug alpha is [v0.1.0-alpha.4](https://github.com/hk121902-stack/finance-ministry/releases/tag/v0.1.0-alpha.4).
Download the debug APK from its assets. The release includes its SHA-256 checksum,
signing-certificate details, and license notices. Physical-device testing is pending.

## What works today

- Capture new financial SMS after you explicitly enable SMS access.
- Save a normalized transaction first, then send an optional native notification with **View** and **Edit**.
- Keep uncertain detections in **Review**, including transactions whose amount could not be safely extracted.
- Add manual transactions with money-in/out choices, date/time pickers and optional details.
- Use separate Home and Settings screens, light/dark themes and unsaved-edit warnings.
- Edit records, keep correction history, delete individual records, or erase all local data.
- Browse history in 100-record pages, filter manual/review/edited entries, and see daily/monthly debit and credit totals.
- Extract supported masked account hints and recipient labels; conservatively link full-amount refunds/reversals when strong matching evidence exists.
- Store the ledger in an encrypted Room/SQLCipher database with keys protected by Android Keystore.

The app has no account, cloud sync, ads, analytics, payment initiation, bank API connection, or Internet permission. It reads **new incoming SMS only**; it does not import message history or require becoming the default SMS app.

## Try the alpha

Requires **Android 8.0 (API 26) or newer**. Current runtime verification is on a Pixel 9a Android 16 emulator; physical-device and OEM testing is still pending.

1. Open [Releases](https://github.com/hk121902-stack/finance-ministry/releases) and choose the newest **pre-release**. Download its `finance-ministry-*-debug.apk`, not the source-code ZIP.
2. Install the APK on a test device. Android may ask you to allow installations from the app you used to download it.
3. Open Finance Ministry. **+ Add transaction** works immediately without SMS or notification permissions.
4. Open **Settings → Enable SMS capture**, read the disclosure, and continue through Android's SMS permission prompt. **Pause SMS capture** means capture is currently enabled.
5. In **Settings**, enable **Recording notifications** and allow the separate Android prompt. If blocked, use **Allow Android notifications** or **Open notification settings**. The in-app switch and Android permission are independent; both must be on for notifications.
6. New eligible messages are recorded automatically. Open a row or its notification to review or correct it.

Manual example: enter `250.50`, keep **Money out**, and save. Cash, Other and Successful are the defaults; transaction type and payment status are under **More details**. The ledger shows **Confirmed by you**.

Emulator-only automatic example (this command does not send a real SMS):
```sh
adb -s emulator-5554 emu sms send 5551234 "INR 314.15 debited from your account via UPI"
```
With capture enabled, this produces a **Money out · Saved automatically** transaction. With notifications allowed, it also produces a notification. A message such as `Your account debited by 250` goes to **Review** because the currency amount is uncertain.

Known parser gap: an SMS saying your account was debited and the recipient was
credited may be missed. Add it manually. The alpha does not guarantee complete SMS
coverage, and updating does not reprocess old messages. See the [open issues](docs/ROADMAP.md#confirmed-open-parser-issues).

### Updating

Download the next APK from the same repository and install it over the existing app. Official alpha releases use one persistent signing identity and an increasing Android version code. Local developer builds use a different key and may not update an official APK in place.

**Do not uninstall to troubleshoot an update without understanding the consequence:** uninstalling or using Erase all removes the local ledger. There is currently no export, backup, cloud restore, or data recovery. Release notes will call out known upgrade restrictions. Moving to a future production signing identity may require a separate migration plan.

### Debug-build limits

These APKs are deliberately debuggable. An authorized debugging connection can inspect app state; encryption does not make a debug build equivalent to a hardened production build. Use a test device and sample transactions while evaluating it.

The app does not keep raw SMS bodies or senders in its database. Manually entered notes and labels are stored locally and can contain whatever you type; avoid pasting sensitive messages or identifiers. See [Privacy](docs/PRIVACY.md).

## Known limitations

- English heuristic parsing, primarily INR; no measured production-accuracy guarantee.
- No historical SMS import, iOS app, WhatsApp/Telegram/Discord capture, or automatic bank reconciliation.
- Refund/reversal linking and SMS account-hint extraction remain unfinished. Review these records manually.
- Monthly summaries exclude failed, reversed, pending, transfer and needs-review records. They are transaction summaries, not a verified account balance.
- The ledger shows the latest 500 entries; older-history navigation and daily summaries are planned.
- OEM background restrictions, multipart edge cases, permission lifecycle behavior, and real-phone reliability need broader testing.
- No automatic updater; check Releases for updates.

## Build from source

Use **JDK 17**, Android SDK platform **37.0** (compile SDK 37), and the checked-in **Gradle 9.5.0 wrapper**. Dependency versions are pinned in [the version catalog](android-app/gradle/libs.versions.toml). The first build downloads dependencies and requires network access on the build machine.

Open the `android-app` directory in Android Studio, or set `ANDROID_HOME` to your SDK installation and run:

```sh
cd android-app
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

On Windows, use `.\gradlew.bat` instead of `./gradlew`. The APK is at `android-app/app/build/outputs/apk/debug/app-debug.apk`.

For connected Android tests, use a **dedicated disposable emulator**:
```sh
cd android-app
./gradlew connectedDebugAndroidTest
```
Instrumentation grants test permissions and creates/cleans synthetic records. Do not run it against a phone containing an important ledger. The host-driven SMS end-to-end test is skipped unless explicitly enabled; see [release verification](docs/RELEASING.md).

## Project layout

| Path | Purpose |
| --- | --- |
| `android-app/app/src/main` | Kotlin/Compose application, parser, storage and receiver |
| `android-app/app/src/test` | JVM tests using synthetic inputs |
| `android-app/app/src/androidTest` | Real Android storage, UI and capture checks |
| `android-app/app/schemas` | Versioned Room schemas for future migrations |
| `.github/workflows` | CI and manual alpha-release workflow |
| `docs` | Public privacy, roadmap and release guidance |

Private SMS backups, research datasets, evaluation workbooks, local configuration and signing keys are intentionally excluded.

## Releases and contributions

Development is **maintainer-led during alpha**. Bug reports and focused feedback are welcome now, using synthetic examples only. Broad external feature contributions will open when the app is ready for general use; discuss changes before starting a pull request. See [Contributing](CONTRIBUTING.md).

Releases use tags such as `v0.1.0-alpha.1` and an accompanying [changelog](CHANGELOG.md). The maintainer runs the release workflow to build a consistently signed debug APK, SHA-256 checksums and a draft pre-release, then reviews and publishes it. See [Releasing](docs/RELEASING.md).

## License

Finance Ministry's original source code is available under the [MIT License](LICENSE). Bundled dependencies retain their own licenses; see [third-party notices](docs/THIRD_PARTY_NOTICES.md).
