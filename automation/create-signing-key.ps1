$ErrorActionPreference = 'Stop'
$keytool = Get-Command keytool -ErrorAction SilentlyContinue
$keytoolPath = if ($keytool) { $keytool.Source } else { $null }
if (-not $keytool -and $env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME 'bin/keytool.exe'
    if (Test-Path -LiteralPath $candidate) { $keytoolPath = $candidate }
}
if (-not $keytoolPath) { throw 'Install JDK 17, then reopen PowerShell and run this script again.' }
$destination = Join-Path $env:USERPROFILE 'feiyang-release.jks'
if (Test-Path -LiteralPath $destination) { throw "Existing key preserved: $destination" }
& $keytoolPath -genkeypair -v -storetype JKS -keystore $destination -alias feiyang -keyalg RSA -keysize 3072 -validity 10000
if ($LASTEXITCODE -ne 0) { throw 'Key generation failed.' }
Write-Host "Back up $destination and both passwords. Alias: feiyang"
Write-Host 'Copy its Base64 into the GitHub SIGNING_KEYSTORE_BASE64 secret with:'
Write-Host '[Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $env:USERPROFILE ''feiyang-release.jks''))) | Set-Clipboard'
