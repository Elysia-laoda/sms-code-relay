# Generates the release signing key for the sms-relay app.
# The keystore and its properties file live outside the repository; the password is
# written only into that properties file and is never printed.
# Two traps this file exists to avoid, both hit for real:
#   1. building the body with -join collapses the four lines into one;
#   2. java.util.Properties treats a lone backslash as an escape, so the Windows path
#      must be written doubled or Gradle looks for 'D:androidkeyssmsrelay-release.jks'.

#psr7-selfroute-begin
# This script has to run under PowerShell 7. Launched by Windows PowerShell 5.1, it restarts
# itself in pwsh with the same arguments. That covers the callers a PATH shim cannot reach: a
# hardcoded System32 path, someone else's scheduled task, another program, a double-click in a
# guest. If pwsh is not installed the script simply continues on 5.1.
if ($PSVersionTable.PSEdition -ne 'Core') {
    $__psr7 = 'C:\Program Files\PowerShell\7\pwsh.exe'
    if (-not (Test-Path -LiteralPath $__psr7)) { $__psr7 = Join-Path $env:LOCALAPPDATA 'Microsoft\WindowsApps\pwsh.exe' }
    if (Test-Path -LiteralPath $__psr7) {
        $__psrArg = @()
        $__psrFound = $false
        $__psrCmd = [Environment]::GetCommandLineArgs()
        for ($__psrI = 1; $__psrI -lt $__psrCmd.Count; $__psrI++) {
            $__psrHit = $false
            try { $__psrHit = ([IO.Path]::GetFullPath($__psrCmd[$__psrI].Replace('/', '\')) -ieq [IO.Path]::GetFullPath($PSCommandPath)) } catch { }
            if ($__psrHit) {
                $__psrFound = $true
                if ($__psrI + 1 -lt $__psrCmd.Count) { $__psrArg = $__psrCmd[($__psrI + 1)..($__psrCmd.Count - 1)] }
                break
            }
        }
        # Redirect only when the caller's own invocation can be rebuilt faithfully. Reached through
        # -Command ("& 'x.ps1' -Tag y") the path never appears as an argument of its own, and
        # restarting with no arguments would silently drop them -- worse than staying on 5.1.
        if ($__psrFound) {
            & $__psr7 -NoProfile -ExecutionPolicy Bypass -File $PSCommandPath @__psrArg
            exit $LASTEXITCODE
        }
    }
}
#psr7-selfroute-end
$ErrorActionPreference = 'Stop'

$dir     = 'D:\android\keys'
$ks      = Join-Path $dir 'smsrelay-release.jks'
$props   = Join-Path $dir 'smsrelay-release.properties'
$keytool = 'D:\android\jdk17\bin\keytool.exe'
New-Item -ItemType Directory -Force -Path $dir | Out-Null

if (Test-Path $ks)    { Remove-Item -LiteralPath $ks -Force }
if (Test-Path $props) { Remove-Item -LiteralPath $props -Force }

$bytes = New-Object byte[] 24
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$pw = ($bytes | ForEach-Object { $_.ToString('x2') }) -join ''

& $keytool -genkeypair -v -keystore $ks -alias smsrelay -keyalg RSA -keysize 2048 -validity 10000 -storepass $pw -keypass $pw -dname 'CN=sms relay, O=elysia, C=CN'
if ($LASTEXITCODE -ne 0) { throw "keytool failed with exit code $LASTEXITCODE" }

$ksEsc = $ks.Replace('\', '\\')
$body = @'
storeFile=__KS__
storePassword=__PW__
keyAlias=smsrelay
keyPassword=__PW__
'@
$body = $body.Replace('__KS__', $ksEsc).Replace('__PW__', $pw)
if (-not $body.EndsWith([string][char]10)) { $body = $body + [string][char]10 }
[System.IO.File]::WriteAllText($props, $body, (New-Object System.Text.UTF8Encoding($false)))

$raw = [System.IO.File]::ReadAllBytes($props)
$lf  = ($raw | Where-Object { $_ -eq 10 }).Count
Write-Output "keystore  : $ks"
Write-Output "properties: $props  bytes=$($raw.Length)  lf=$lf  bom=$($raw[0] -eq 0xEF)"
Get-Content $props | ForEach-Object { $_ -replace '(?<=Password=).*', '***' }
