# Puts the release key from .local/android-signing into the GitHub repository secrets that
# release.yml needs (EKW_KEYSTORE_BASE64, EKW_KEYSTORE_PASSWORD). Values are never printed.
#   With GitHub CLI (gh, logged in): sets both secrets directly.
#   Without it: copies each value to the clipboard in turn and opens the secrets page;
#   paste it into "New repository secret", press Enter here for the next one.
param(
    [string]$Repo = 'maxpowerart/ekwalizer'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$signing = Join-Path $root '.local\android-signing'
$propsFile = Join-Path $signing 'key.properties'
if (-not (Test-Path $propsFile)) { throw 'No release key: run tools/build-release.ps1 -NewKeystore first' }
$props = @{}
foreach ($line in Get-Content $propsFile) {
    if ($line -match '^\s*([^=]+?)\s*=\s*(.*)$') { $props[$Matches[1]] = $Matches[2] }
}
$store = $props['storeFile']
if (-not (Test-Path $store)) { throw "Keystore not found: $store" }
$secrets = [ordered]@{
    EKW_KEYSTORE_BASE64   = [Convert]::ToBase64String([IO.File]::ReadAllBytes($store))
    EKW_KEYSTORE_PASSWORD = $props['storePassword']
}

if (Get-Command gh -ErrorAction SilentlyContinue) {
    foreach ($name in $secrets.Keys) {
        # Through stdin, so the value never appears on a command line or in the output.
        $secrets[$name] | gh secret set $name --repo $Repo
        if ($LASTEXITCODE -ne 0) { throw "gh secret set $name failed (is gh logged in? gh auth login)" }
        "Set $name"
    }
} else {
    Start-Process "https://github.com/$Repo/settings/secrets/actions/new"
    foreach ($name in $secrets.Keys) {
        Set-Clipboard -Value $secrets[$name]
        Read-Host "Name: $name - value is in the clipboard. Paste it on the GitHub page, click 'Add secret', then press Enter"
    }
    Set-Clipboard -Value ''
    'Clipboard cleared.'
}
"Done. Check: https://github.com/$Repo/settings/secrets/actions"
