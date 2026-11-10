param(
    [Parameter(Mandatory=$true)]
    [ValidateSet('SIGNING_KEYSTORE_BASE64', 'SIGNING_KEY_ALIAS', 'SIGNING_STORE_PASSWORD', 'SIGNING_KEY_PASSWORD')]
    [string]$Name
)
$ErrorActionPreference = 'Stop'
$path = Join-Path (Split-Path $PSScriptRoot -Parent) '.signing/github-secrets.json'
$secrets = [IO.File]::ReadAllText($path) | ConvertFrom-Json
Set-Clipboard -Value $secrets.$Name
Write-Output "$Name copied to clipboard. Paste it into the matching GitHub Secret."
