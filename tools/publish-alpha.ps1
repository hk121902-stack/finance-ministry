# Launch the reviewed, main-only GitHub workflow; it creates a draft, not a public release.
$ErrorActionPreference = 'Stop'
$repo = 'hk121902-stack/finance-ministry'
& gh workflow run alpha-release.yml --repo $repo --ref main
if ($LASTEXITCODE -ne 0) { throw 'Could not start alpha release workflow.' }
Write-Output "Release build started: https://github.com/$repo/actions/workflows/alpha-release.yml"
Write-Output 'When it succeeds, review the draft under Releases before publishing it.'
