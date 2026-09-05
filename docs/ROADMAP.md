# Roadmap

This is a direction of travel, not a dated delivery commitment.

## Available in the debug alpha

- New SMS capture with explicit consent and separate notification permission.
- Encrypted local ledger, review states and duplicate detection.
- Manual transactions, corrections, basic filters and monthly totals.
- Native saved-record notifications with View/Edit.

## Before general use

- Expand synthetic parser regressions and human-reviewed accuracy evaluation.
- Validate Redmi and other physical devices, OEM background behavior and multipart SMS.
- Improve permission-status clarity and notification recovery guidance.
- Extract masked account hints and handle refund/reversal links conservatively.
- Add older-history navigation and clearer daily/monthly summaries.
- Validate concurrency around capture, edits and erasure; expand cold/warm navigation checks.
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
