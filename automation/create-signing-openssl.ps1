$ErrorActionPreference = 'Stop'
$signingRoot = Join-Path (Split-Path $PSScriptRoot -Parent) '.signing'
if ((Test-Path -LiteralPath (Join-Path $signingRoot 'feiyang-release.p12')) -or (Test-Path -LiteralPath (Join-Path $signingRoot 'github-secrets.json'))) { throw 'Existing signing identity preserved. Do not regenerate a published signing identity.' }
$opensslPath = @('D:\Git\usr\bin\openssl.exe', 'C:\Program Files\Git\usr\bin\openssl.exe') | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $opensslPath) { throw 'OpenSSL not found.' }
New-Item -ItemType Directory -Force -Path $signingRoot | Out-Null
$ownerSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
& icacls.exe $signingRoot /inheritance:r /grant:r "*$($ownerSid):(OI)(CI)F" '*S-1-5-18:(OI)(CI)F' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not restrict signing directory permissions.' }
$workspaceOwner = (Get-Acl (Split-Path $PSScriptRoot -Parent)).Owner
$workspaceOwnerSid = (New-Object Security.Principal.NTAccount($workspaceOwner)).Translate([Security.Principal.SecurityIdentifier]).Value
& icacls.exe $signingRoot /grant "*$($workspaceOwnerSid):(OI)(CI)F" | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not grant workspace owner access.' }
$random = [Security.Cryptography.RandomNumberGenerator]::Create()
$passwordBytes = New-Object byte[] 32
$random.GetBytes($passwordBytes)
$random.Dispose()
$password = [Convert]::ToBase64String($passwordBytes)
$env:FEIYANG_SIGNING_PASSWORD = $password
$keyPath = Join-Path $signingRoot 'temporary-key.pem'
$certPath = Join-Path $signingRoot 'certificate.pem'
$p12Path = Join-Path $signingRoot 'feiyang-release.p12'
try {
    # Windows PowerShell wraps native stderr progress as errors, even on success.
    $ErrorActionPreference = 'Continue'
    & $opensslPath req -quiet -newkey rsa:3072 -x509 -sha256 -days 10000 -subj '/CN=Feiyang Messenger/O=Feiyang/C=CN' -passout env:FEIYANG_SIGNING_PASSWORD -keyout $keyPath -out $certPath 2> (Join-Path $signingRoot 'generation.log')
    $requestExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($requestExit -ne 0) { throw 'Certificate generation failed; see private generation.log.' }
    & $opensslPath pkcs12 -export -name feiyang -inkey $keyPath -in $certPath -out $p12Path -passin env:FEIYANG_SIGNING_PASSWORD -passout env:FEIYANG_SIGNING_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw 'PKCS12 export failed.' }
    & $opensslPath pkcs12 -in $p12Path -passin env:FEIYANG_SIGNING_PASSWORD -noout
    if ($LASTEXITCODE -ne 0) { throw 'PKCS12 verification failed.' }
    $secrets = [ordered]@{
        SIGNING_KEYSTORE_BASE64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($p12Path))
        SIGNING_KEY_ALIAS = 'feiyang'
        SIGNING_STORE_PASSWORD = $password
        SIGNING_KEY_PASSWORD = $password
    }
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [IO.File]::WriteAllText((Join-Path $signingRoot 'github-secrets.json'), ($secrets | ConvertTo-Json), $utf8)
    Write-Output 'Created and verified .signing/feiyang-release.p12. Credentials saved privately in .signing/github-secrets.json.'
    Write-Output 'Back up the .signing folder to a secure separate location. No credentials were printed.'
} finally {
    Remove-Item Env:FEIYANG_SIGNING_PASSWORD -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath $keyPath) { Remove-Item -LiteralPath $keyPath -Force }
}
