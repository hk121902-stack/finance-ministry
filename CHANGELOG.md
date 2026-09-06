# Changelog

User-visible changes are recorded here. Pre-1.0 releases are experimental.

## 0.1.0-alpha.3

Includes the alpha.2 improvements below and fixes a small-screen layout issue:
the ledger page scrolls and reserves visible height for transaction history instead
of letting the controls shrink it to zero. Alpha.2 was withheld as an unpublished
draft after this issue was found during final artifact verification.

## 0.1.0-alpha.2 (unpublished draft)

### Parser reliability

- Recognize structured sent-payment messages with a masked source account and recipient.
- Exclude available-balance abbreviations from transaction amounts, without discarding
  a later transaction amount simply because a balance appeared earlier.
- Preserve known direction, status and channel when transaction amounts are ambiguous.
- Extract masked account hints and conservative recipient labels from supported templates;
  persist those labels in the encrypted ledger.
- Exclude the structured "Not You?" security footer from channel inference.
- Add synthetic parser and encrypted-storage regressions. Existing SMS records are not
  reprocessed; manual corrections remain authoritative.
- Keep the SQLCipher connection password valid until database close so additional
  pooled connections can open; still clear temporary input buffers immediately.

### Ledger completeness

- Browse all saved records in 100-record pages with stable ordering for equal timestamps.
- Apply review/manual/edited filters before pagination so older matches remain accessible.
- Show today's debit and credit totals alongside monthly totals, independently of the
  current history page or filter. Exclude transfers and non-final/review records.
- Verify history beyond 500 records and daily boundaries, edits and deletions using
  synthetic records in isolated encrypted test databases.

### Refund/reversal links

- Link full-amount adjustments only with one eligible original sharing an explicit
  reference hash, SMS sender, masked account and known channel. Ambiguous, partial,
  differently-sourced or unmatched adjustments stay reviewable.
- Show a link to the original record. Linked reversals exclude the original debit
  from totals; refunds remain separate debit/credit entries, not net spending.
- Remove automatic links on edits/deletions; never change a user's corrected fields.
- Migrate encrypted schema 1 to 2 by adding nullable fields, preserving old records
  and corrections. Older records have no reference hashes and cannot be backfilled
  because raw SMS is not retained.

### Permission and navigation reliability

- Refresh capture/notification availability and ledger totals when the activity resumes.
- Explain blocked Android notifications and provide a notification-settings shortcut;
  capture and manual entry remain independent of notification availability.
- Consume notification navigation requests so they do not replay after recreation.
- Handle deleted-record notification actions without leaving a stale edit form open.
- Return to the newest history page after saving a manual entry or correction.
- Verify notification PendingIntent targeting/recreation, deleted-record handling,
  notification failure isolation, and background system-SMS capture on the emulator.
  OEM behavior and real-device latency still require validation.

### Alpha.2 candidate validation

- Add isolated encrypted-storage concurrency checks: duplicate deliveries produce
  one save callback, corrections survive concurrent duplicate capture, simultaneous
  edits preserve a complete audit chain, and capture/erasure races leave no database
  or keys behind. New explicit manual entry remains usable after erasure.
- Candidate version `0.1.0-alpha.2` / code 2 uses the persistent alpha signing identity.
- In-place installation over the published alpha.1 APK passed on an isolated Android
  16 emulator without uninstalling. Edited record, correction history, encryption keys
  and schema migration survived, including a subsequent cold process restart.
- Physical-device validation remains pending; emulator results are not an OEM guarantee.
- Verify 20 synthetic three-segment SMS deliveries, each producing one transaction
  and a notification, on the isolated Android 16 emulator. Observed median/p95/max:
  149/217/1,830 ms without a concurrent build. An earlier run exceeded five seconds;
  this sample does not close the real-device latency gate.
- Verify SMS restarts an ordinarily killed background app process and saves/notifies
  a credit while preserving the pre-existing upgraded ledger record. Force-stop,
  reboot and OEM battery restrictions are not covered by this check.

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
