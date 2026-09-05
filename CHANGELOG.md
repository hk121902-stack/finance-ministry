# Changelog

User-visible changes are recorded here. Pre-1.0 releases are experimental.

## Unreleased

Next fixes and improvements will be listed here before the next release.

## 0.1.0-alpha.1

First open-source debug alpha.

### Added

- On-device parsing of new incoming financial SMS with visible review states.
- Encrypted Room/SQLCipher ledger and Android Keystore-protected secrets.
- HMAC source deduplication and save-before-notify native View/Edit notifications.
- Manual add, edit/confirm, correction history, delete and erase-all flows.
- Monthly summaries and filters for manual, review and edited records.
- Public build instructions, privacy documentation, contribution policy, CI and release workflow.

### Validation and limits

- Initial core verified with 10 JVM tests and 7 Android 16 emulator tests, including a
  synthetic system-SMS-to-record-and-notification path and real manual entry.
- Release/build CI results are linked from GitHub Actions; do not interpret JVM CI as
  a substitute for physical-device testing.
- Debug build only. No production accuracy guarantee, historical SMS import, backup,
  export, automated refund linking or verified physical-device support yet.
