# Contributing

Finance Ministry is open source, with maintainer-led development during its early alpha.

## What is welcome now

- Bug reports with app version, Android version, device model and clear reproduction steps.
- Feedback about capture, review, manual entry, notifications and accessibility.
- Small documentation corrections. Discuss code changes in an issue before investing time.

Broader feature contributions will open once the app is ready for general use. This is
a project-maintenance policy, not an additional restriction on the MIT license: you
can still fork and modify the code under that license. A pull request is not a promise
of review or merge, and there is no support-response SLA.

## Protect users' data

Use invented messages and amounts. Never attach SMS backups, real sender names,
phone/account numbers, UPI IDs, OTPs, database files, keystores or unredacted logs.
Replace every identifying value before posting a screenshot. If a parser template
fails, recreate the sentence with synthetic details rather than copying a real SMS.

Report exploitable security issues through [private vulnerability reporting](https://github.com/hk121902-stack/finance-ministry/security/advisories/new), not public issues.

## Development expectations

1. Start from current `main` and keep changes focused.
2. Preserve the local-only design, explicit SMS disclosure, independent manual entry,
   save-before-notify ordering and visible handling of uncertainty.
3. Add meaningful synthetic regression coverage for behavior changes.
4. Run `python tools/verify_public_tree.py` after staging, then from `android-app`
   run `./gradlew testDebugUnitTest lintDebug assembleDebug`.
5. For Android behavior changes, run the relevant connected tests on a disposable emulator.
6. Describe the problem, solution, checks and remaining limitations in the PR. Include
   screenshots for visible changes using synthetic records.

Do not include unrelated formatting, private datasets or generated builds. Discuss
schema changes and their non-destructive migrations before implementation. Keep
communication respectful and concrete; harassment and disclosure of others' private
information are not acceptable.

Contributions submitted for inclusion are under this repository's MIT license.
