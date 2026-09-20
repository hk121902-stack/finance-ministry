# Changelog

User-visible changes are recorded here. Pre-1.0 releases are experimental.

## Unreleased

## 0.1.0-alpha.12

- Refine the ledger into the approved Quiet Ledger presentation with calmer
  surfaces, smoother rounded cards, lighter dividers, clearer dark-theme contrast,
  flatter transaction rows and a compact Add transaction action.
- Make the Overview summary explicitly describe **Your share of spending** and
  clarify how personal, family and group shares contribute to it.
- Make filtered transaction-result totals use the same confirmed-payment rules as
  Overview, excluding review items, failed payments, self transfers, card
  repayments and reversed originals from Money in and Money out.
- Add multi-select filtering by registered payment source, including inactive
  sources, alongside the existing direction, purpose, origin and category filters.
- Improve transaction-result labels and helper text so filtered totals are easier
  to compare with the whole-month Overview.
- Preserve the first-use welcome flow without showing a duplicate Add action.

## 0.1.0-alpha.11

- Redesign the ledger around three focused destinations: **Overview** for the
  selected month, **Transactions** for search and filters, and an all-dates
  **Review** queue for uncertain records.
- Add transaction search plus clearly scoped Money out and Money in subtotals
  for the current result set. These do not change the Overview's monthly totals.
- Add an optional on-device preferred name for the short home greeting.
- Make category/purpose updates available directly from transaction rows while
  preserving an uncertain record's review state.
- Add multi-select category filters combined with money flow, purpose and origin.
  Filters narrow transactions without changing the whole-month dashboard totals.
- Add Flat expenses as a transaction category, separate from payment purpose.
- Add Family as a payment purpose and transaction filter. Family expenses count
  toward your spending and do not create repayment balances.
- Align the native ledger with the approved calm-ledger design: a dominant monthly
  spending summary, separate all-date amount owed, an explicit review task, and
  lighter divider-separated transaction rows.
- Keep manual entry focused on amount, label, category and payment purpose; date,
  source and notes remain available under More details.
- Make first-run setup clearly optional and keep adding a transaction immediately
  available from the welcome card.

## 0.1.0-alpha.10

- Add Money out and Money in transaction filters. They work together with the
  existing purpose and origin filters.

## 0.1.0-alpha.9

- Show selected-month money in and money out on the dashboard; open the totals
  explanation from an accessible info button.
- Add a notification Categorize shortcut with category, payment purpose, and group
  share fields together. Label changes preserve financial details and review status.
- Wrap payment-purpose choices so every option is discoverable on narrow screens.

## 0.1.0-alpha.8

- Complete a review-response release hardening pass before packaging:
  - preserve exact timestamps when users edit only non-financial fields,
  keep imported/self-transfer records stable, and make source assignment safer for
  manual and imported entries.
- Add source-management UX and guided onboarding:
  - user-registered payment sources, per-transaction source attribution,
  and an explicit first-use guide.
- Improve reminders and review workflows with deterministic scheduling, cancellation
  and state refresh after app resume.
- Tighten parser behavior for common edge cases from Indian bank/card SMS, including
  known ICICI, BOB, Kotak, UPI and card variants; uncertain/unsupported forms stay
  in Review instead of silently auto-saving.
- Fix repayment/foreign-state presentation and unknown status handling, including
  improved totals behavior and clearer debt expectations.
- Add large-font/filter UX polish and additional regression coverage for
  repository and receiver flows.

## 0.1.0-alpha.6

- Parser version 6 adds researched Indian card/account-alert variants, including
  Kotak date-before-merchant, BOBCARD, SBI, Federal, IDFC FIRST and YES Bank card
  payments. Balance, available-limit, outstanding and cycle-total amounts are ignored.
  Unsupported foreign-currency card alerts and unfamiliar card layouts require review.
- Add regression cases for selected public-format variants across PNB, IndusInd,
  Indian Bank, NSDL, Punjab & Sind Bank, Kerala Bank and Bank of India. This is not
  a claim of complete bank coverage; source provenance and negative variants are
  recorded in the roadmap.
- Validated the three reported Kotak and BOBCARD alerts in the Android emulator.
  Automated evidence does not establish physical-device reliability or complete bank coverage.

## 0.1.0-alpha.5

- Add opt-in last-three-calendar-month SMS import in Settings, with separate inbox
  permission/disclosure, normalized preview, confirmation, duplicate checks,
  original receipt dates, cancellation and batch undo preserving edits. No import
  notification flood or raw SMS storage. Add non-destructive database migration 2→3.

- Recognize the bounded ICICI masked-account debit / named-recipient credit UPI
  template as one outgoing payment. Preserve conservative handling of mixed events,
  incoming credits and OTP/scheduled/negated messages.
- Expand bounded recognition for BOB Dr./Cr. payments, Axis and ICICI card spends,
  HDFC card-UPI/received/deposited/mandate alerts, Pluxee wallet spends and fees,
  posted and initiated refunds, card repayments and secondary payment receipts.
- Require movement evidence instead of treating credit/debit card product names
  or standalone processing text as transactions. Support compact INR amounts,
  single-digit wallet timestamps and balance/limit labels containing "is".
- Classify card repayments separately and exclude them from recorded totals.
  Keep supported merchant receipts and card-autopay confirmations in review to
  avoid automatically double-counting a corresponding bank/card alert. This is
  conservative review routing, not cross-source automatic reconciliation.
- Parser version is now 5. Existing saved records are not automatically changed;
  previously misclassified repayments can be corrected using Card Repayment.
- Old SMS are read only through
  the explicitly confirmed import flow; existing saved records are not reprocessed.
- Automated validation: 35 JVM tests and 24 distinct emulator tests passed before
  release preparation. Manual/physical-device testing is waived for this debug alpha,
  not claimed as completed. Install over the previous official alpha; do not uninstall.

## 0.1.0-alpha.4

- Separate Home from Settings, with recorded totals, date-grouped history and
  clearer review prompts. Keep existing 100-record history pagination.
- Simplify manual entry with money-in/out choices, native date/time pickers,
  optional advanced details, a keyboard-aware Save bar and a discard warning.
- Introduce a neutral teal light/dark theme and readable transaction labels.
  Widgets and general-use readiness are not part of this increment.

- Recognize structured `Spent Rs… On … Card … At … On …` and amount-first
  card-spend messages as card merchant debits, with masked last-four hints and
  merchant labels. Reject standalone spend chatter, offers and OTP messages.
- Exclude inline as well as newline-separated `Not You?` security instructions
  from transaction field inference. Preserve review handling for multiple amounts.
- Add an opt-in parser evaluation harness that loads private inputs externally;
  no message corpus is bundled in the app or committed to the public repository.

Known limitations: messages describing both the user's account being debited and
the recipient being credited can still be rejected. Add missed transactions manually.
Parser coverage and PRD accuracy targets are not independently human-verified.
Physical-device testing, widgets, progressive history loading and field-level
validation polish remain pending. No raw SMS history is retained for reprocessing.
Install over the previous official alpha; do not uninstall an important ledger.

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
