param(
    [Parameter(Mandatory = $true)][string]$AdbPath,
    [Parameter(Mandatory = $true)][ValidatePattern('^emulator-[0-9]+$')][string]$Serial,
    [ValidateRange(1,20)][int]$Repetitions = 1
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) { throw 'ADB executable not found.' }
$devices = & $AdbPath devices
if (-not ($devices -match "^$([regex]::Escape($Serial))\s+device$")) { throw 'The requested emulator is not connected.' }
$taskTemp = Join-Path ([IO.Path]::GetTempPath()) ('finance-sms-check-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskTemp | Out-Null
$output = Join-Path $taskTemp 'instrumentation.txt'
$errors = Join-Path $taskTemp 'adb-errors.txt'
$arguments = @('-s', $Serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
    'in.financeministry.app.SmsBroadcastE2eTest', '-e', 'runSmsE2e', 'true', '-e', 'smsRepetitions', "$Repetitions",
    'in.financeministry.app.test/androidx.test.runner.AndroidJUnitRunner')
$initialMarker = ((& $AdbPath -s $Serial shell run-as in.financeministry.app cat files/synthetic_sms_test_ready 2>$null) -join '').Trim()
$launch = @{FilePath=$AdbPath; ArgumentList=$arguments; PassThru=$true; RedirectStandardOutput=$output; RedirectStandardError=$errors}
if ($IsWindows -or $env:OS -eq 'Windows_NT') { $launch.WindowStyle = 'Hidden' }
$process = Start-Process @launch
$sent = [Collections.Generic.HashSet[string]]::new()
$deadline = [DateTime]::UtcNow.AddMinutes(4)
while (-not $process.HasExited -and [DateTime]::UtcNow -lt $deadline) {
    $marker = ((& $AdbPath -s $Serial shell run-as in.financeministry.app cat files/synthetic_sms_test_ready 2>$null) -join '').Trim()
    if ($marker -ne $initialMarker -and $marker -match '^[0-9a-f-]{36}:([0-9]+)$' -and $sent.Add($marker)) {
        # Synthetic input only. Never read or transmit the user's SMS inbox.
        & $AdbPath -s $Serial emu sms send 999990001 "FM synthetic $marker INR 314.15 debited via UPI" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Emulator SMS injection failed.' }
    }
    Start-Sleep -Milliseconds 250
    $process.Refresh()
}
if (-not $process.HasExited) { throw "Instrumentation timed out. Logs preserved at $taskTemp" }
$result = Get-Content -LiteralPath $output -Raw
Write-Output $result
if ($sent.Count -ne $Repetitions -or $result -notmatch 'OK \(1 test\)' -or $result -match 'FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=') {
    throw "SMS validation did not pass. Logs preserved at $taskTemp"
}
Write-Output "System SMS and notification validation passed ($Repetitions sample(s)). Logs: $taskTemp"
