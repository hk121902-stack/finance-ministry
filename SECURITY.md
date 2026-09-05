# Security

## Supported versions

Only the newest alpha release receives fixes. There is currently no stable release
or security support commitment for older APKs.

## Report privately

Use [GitHub private vulnerability reporting](https://github.com/hk121902-stack/finance-ministry/security/advisories/new).
Include the affected version, expected/actual behavior, steps using synthetic data,
and the likely impact. Do not upload real financial data, private keys or OTPs.

If private reporting is unavailable, open a minimal issue asking the maintainer for
a private reporting channel, without exploit details or personal data. There is no
guaranteed response time; this project has one maintainer.

## Current boundaries

- Alpha APKs are debug builds. Authorized debugging can inspect app state.
- Database encryption protects the on-device file; it does not protect a compromised,
  rooted, unlocked or actively debugged device, or screenshots taken by the user.
- No Internet permission, cloud account, analytics or remote crash reporting is included.
- SMS access is broad at the Android boundary; filtering occurs locally after receipt.
- APK signing keys belong in private storage / GitHub Actions secrets, never Git.
- Only download official builds from this repository's Releases page. Compare checksums
  and the signing certificate information attached to a release when validating an APK.

See [Privacy](docs/PRIVACY.md) for storage and deletion behavior.
