# Builds a signed release APK of ekwalizer into dist/ekwalizer-<version>.apk, plus update.json
# (the in-app updater's channel) and SHA256SUMS.txt — the same assets release.yml publishes.
#   tools/build-release.ps1               -> uses the key in .local/android-signing
#   tools/build-release.ps1 -NewKeystore  -> first creates that key (once; keep a backup copy:
#                                            without it installed apps cannot be updated)
# Same signing layout as KwanimeGo. Passwords are random and never printed.
param(
    [switch]$NewKeystore
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$dist = Join-Path $root 'dist'
$tmp = Join-Path $root '.local\tmp'
New-Item -ItemType Directory -Force $dist, $tmp | Out-Null
# Gradle/Java loopback sockets need a writable temp dir.
$env:TEMP = $tmp; $env:TMP = $tmp
$env:GRADLE_OPTS = "-Djdk.net.unixdomain.tmpdir=$tmp -Djava.io.tmpdir=$tmp"
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr' }
$keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'

$signing = Join-Path $root '.local\android-signing'
$props = Join-Path $signing 'key.properties'
if ($NewKeystore) {
    $store = Join-Path $signing 'ekwalizer-release.jks'
    if (Test-Path $store) { throw "Keystore already exists: $store" }
    New-Item -ItemType Directory -Force $signing | Out-Null
    $bytes = New-Object byte[] 24
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $password = [Convert]::ToBase64String($bytes) -replace '[+/=]', 'x'
    # Through cmd: keytool reports progress on stderr, which PowerShell 5 turns into errors.
    $null = & cmd /c "`"$keytool`" -genkeypair -keystore `"$store`" -storepass $password -keypass $password -alias ekwalizer -keyalg RSA -keysize 4096 -validity 36500 -dname `"CN=ekwalizer, O=private`" 2>&1"
    if (-not (Test-Path $store)) { throw 'keytool did not create the keystore' }
    Set-Content -Encoding ascii $props @(
        "storeFile=$($store -replace '\\', '/')", "storePassword=$password", 'keyAlias=ekwalizer', "keyPassword=$password")
    & cmd /c "`"$keytool`" -list -v -keystore `"$store`" -storepass $password -alias ekwalizer 2>&1" |
        Select-String 'SHA256:' | ForEach-Object { "Release key $($_.Line.Trim())" }
}
if (-not (Test-Path $props)) { throw 'No release key: run tools/build-release.ps1 -NewKeystore once' }

$out = & cmd /c "cd /d `"$root`" && .\gradlew.bat assembleRelease --console=plain 2>&1"
if ($LASTEXITCODE -ne 0) { $out | Select-Object -Last 30; throw 'gradle assembleRelease failed' }

$version = (Select-String -Path (Join-Path $root 'app\build.gradle') -Pattern "def appVersion = '([^']+)'").Matches[0].Groups[1].Value
$name = "ekwalizer-$version.apk"
$apk = Join-Path $root 'app\build\outputs\apk\release\app-release.apk'
$apkOut = Join-Path $dist $name
Copy-Item -Force $apk $apkOut
"Built $apkOut ($([math]::Round((Get-Item $apkOut).Length / 1KB)) KB)"

# Release notes: the CHANGELOG.md section "## <version>" up to the next "## ".
$lines = Get-Content (Join-Path $root 'CHANGELOG.md') -Encoding utf8
$start = [array]::FindIndex([string[]]$lines, [Predicate[string]]{ param($l) $l -match "^## v?$([regex]::Escape($version))(\s|$)" })
$notes = ''
if ($start -ge 0) {
    $section = $lines[($start + 1)..($lines.Count - 1)]
    $end = [array]::FindIndex([string[]]$section, [Predicate[string]]{ param($l) $l -match '^## ' })
    if ($end -ge 0) { $section = if ($end -gt 0) { $section[0..($end - 1)] } else { @() } }
    $notes = ($section -join "`n").Trim()
}
if (-not $notes) { Write-Warning "CHANGELOG.md has no section for $version"; $notes = "ekwalizer $version." }

$sha = (Get-FileHash $apkOut -Algorithm SHA256).Hash.ToLower()
$manifest = [ordered]@{
    version   = $version
    notes     = $notes
    url       = $name
    sha256    = $sha
    published = (Get-Date -Format 'yyyy-MM-dd')
}
$utf8 = New-Object Text.UTF8Encoding $false
[IO.File]::WriteAllText((Join-Path $dist 'update.json'), ($manifest | ConvertTo-Json), $utf8)
[IO.File]::WriteAllText((Join-Path $dist 'SHA256SUMS.txt'), "$sha  $name`n", $utf8)
"Wrote dist/update.json and dist/SHA256SUMS.txt (sha256 $sha)"
