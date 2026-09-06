# Roadmap

This is a direction of travel, not a dated delivery commitment.

## Available in the debug alpha

- New SMS capture with explicit consent and separate notification permission.
- Encrypted local ledger, review states and duplicate detection.
- Manual transactions, corrections, basic filters and monthly totals.
- Native saved-record notifications with View/Edit.

## Before general use

### Agreed MVP completion checklist

The published alpha is not a completed MVP. Track implementation separately from
human validation; synthetic tests are not a measured bank-template accuracy score.

| Area | Remaining acceptance work |
| --- | --- |
| Recognition and amounts | Validate supported merchant-less debit/credit/transfer templates; keep balances, OTPs, reminders and promotions out of the ledger. Alpha.2 includes bank-format fixes, still awaiting physical validation. |
| Review records | Preserve reliable fields and explain what requires correction without retaining raw SMS. |
| Account and counterparty | Conservative masked hints and supported recipient labels; unknown instead of guesses. |
| Refunds and reversals | Strict full-amount reference-based links implemented in alpha.2; validate real supported templates. Partial, ambiguous and legacy reference-less records remain reviewable. |
| Ledger | Daily totals and filtered 100-record history pages implemented in alpha.2; physical-device usability validation remains. |
| Manual correction | Verify all editable fields, correction history and median add/edit time below 15 seconds. |
| Capture and notifications | Resume status, notification navigation, multipart delivery and receiver restart after ordinary background process kill verified on an isolated Android 16 emulator. Twenty three-segment messages passed with observed median 149 ms / p95 217 ms / max 1,830 ms; an earlier run with a concurrent build exceeded five seconds. Real-device latency and OEM background behavior remain open. |
| Data control and upgrades | Signed upgrade, cold reopening and receiver restart verified on an isolated emulator. Single-process duplicate/correction races, concurrent audit chains and capture/erasure races also pass against encrypted storage. Physical upgrades and broader lifecycle/fault-injection checks remain. |
| Privacy | Verify logs, backups, notifications and network behavior against the privacy notice. |
| Accuracy gate | Human-label 133 candidates plus sampled rejections; evaluate the Android parser against PRD precision/recall and field-accuracy targets. |
| Device gate | Complete Redmi testing and cover at least three OEM/version combinations. |
| User gate | 5–10 target users for two weeks; measure correction burden, missing records and continued-use intent. |
| General-use gate | Harden and verify a non-debug build; review distribution requirements for the chosen channel. |

GitHub debug APKs are the alpha distribution channel. Play Store distribution is a
separate decision and review gate, not a prerequisite claimed for this GitHub alpha.
Backup/export remains a separate scope decision; the current loss-risk disclosure
stays mandatory. Historical SMS import, cloud sync, messenger integrations and
financial-service integrations are not added to the MVP.

### Confirmed open parser issues

- **Account debit / recipient credit conflict — high priority, not fixed.**
  Confirmed on published `0.1.0-alpha.3` in the Android emulator on 2026-09-06:
  the SMS capture test found no transaction after 45 seconds. Direct evaluation
  returned `Reject` / `no_decisive_transaction`, with unknown direction and status.
  The parser treats both movement words as conflicting without identifying whose
  account they describe.
  - Sanitized reproduction: `ICICI Bank Acct XX000 debited for Rs 42.00 on 06-Sep-26; TEST PERSON credited. UPI:000000000000. Call 18002662 for dispute. SMS BLOCK 000 to 9215676766.`
  - Expected: one successful INR 42.00 UPI debit from the user's account, with
    the normal saved-record notification when enabled.
  - Acceptance: infer direction from the account-holder clause; add parser
    regressions and an emulator capture/notification test. Preserve conservative
    handling of genuinely ambiguous or multiple transactions and verify that
    ordinary incoming credits still work.
  - Workaround: add the missed transaction manually. No fix or release is claimed.

### Engineering follow-up

The first usability increment is included in alpha.4: separate
Home/Settings, recorded summaries, date-grouped history, simpler manual entry,
native date/time pickers, clearer review prompts, light/dark colours and unsaved-edit
protection. Existing 100-record paging remains; progressive loading, field-level
validation polish and measured under-15-second add/edit usability remain follow-ups.
Parser reliability issues above are still open. An optional home-screen quick-add
widget comes later; financial summaries require an explicit privacy choice.

- Expand synthetic parser regressions and human-reviewed accuracy evaluation.
- Validate Redmi and other physical devices, OEM background behavior and multipart SMS.
- Improve permission-status clarity and notification recovery guidance.
- Extract masked account hints and handle refund/reversal links conservatively.
- Add older-history navigation and clearer daily/monthly summaries.
- Expand lifecycle/fault-injection coverage beyond the verified single-process capture, edit and erasure races.
- Establish non-destructive database migrations and test release-to-release upgrades.
- Decide on backup/export and recovery before users rely on a long-lived ledger.
- Harden a non-debug production build and decide its distribution/signing strategy.

## Collaboration

For now the maintainer owns implementation and release decisions; bug reports and
focused feedback are welcome. Once general-use readiness is established, publish a
contributor onboarding guide and scoped `help wanted` issues, and open broader code
contributions. See [Contributing](../CONTRIBUTING.md).

Payments, custody, lending, investing and bank API integrations are outside the
current product scope.
