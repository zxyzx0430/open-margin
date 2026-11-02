# Read-only Android 16 / HyperOS accessibility smoke check.
# Run in PowerShell after enabling Accessibility for 清衡 in Android Settings.
# Does not modify permissions, collect view trees, save payment data, or reinstall APKs.
param(
    [string]$Serial = "",
    [string]$PackageName = "com.bookkeeping.app"
)
$ErrorActionPreference = "Stop"
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "adb not found. Install Android platform-tools first."
}
$target = @()
if ($Serial) { $target = @("-s", $Serial) }
function Read-Adb([string[]]$AdbArgs) {
    $output = & adb @target @AdbArgs 2>&1
    if ($LASTEXITCODE -ne 0) { throw "ADB command failed: $($AdbArgs -join ' ')" }
    return ($output | Out-String).Trim()
}
$sdk = Read-Adb @("shell", "getprop", "ro.build.version.sdk")
if ($sdk -ne "36") { throw "This check requires Android 16 (SDK 36); detected SDK $sdk." }
$packagePath = Read-Adb @("shell", "pm", "path", $PackageName)
if ($packagePath -notmatch "^package:") { throw "Package not installed: $PackageName" }
$allowed = Read-Adb @("shell", "settings", "get", "secure", "enabled_accessibility_services")
$authorized = $allowed.Contains($PackageName) -and $allowed.Contains("AccessibilityCaptureService")
$pid = Read-Adb @("shell", "pidof", $PackageName)
$dumpsys = Read-Adb @("shell", "dumpsys", "accessibility")
$matchingLines = @($dumpsys -split "\r?\n" | Where-Object { $_ -like "*AccessibilityCaptureService*" })
$crashLog = Read-Adb @("logcat", "-d", "-b", "crash", "-v", "brief")
$crashMatches = @($crashLog -split "\r?\n" | Where-Object {
    $_ -like "*$PackageName*" -or $_ -like "*AccessibilityCaptureService*"
})
Write-Host "Android SDK: $sdk"
Write-Host "Package installed: yes ($PackageName)"
Write-Host "Permission authorized: $authorized"
Write-Host "Process running: $([bool]$pid)"
Write-Host "Accessibility service mentions in dumpsys: $($matchingLines.Count)"
Write-Host "Crash buffer lines mentioning app/service: $($crashMatches.Count)"
Write-Host ""
Write-Host "Next: in 清衡 -> Settings -> Accessibility, tap '刷新实际运行状态'."
Write-Host "Pass only when the app says '服务已连接', an interaction with WeChat/Alipay"
Write-Host "updates the last page-event timestamp, and no new app/service crash occurs."
Write-Host "A nonzero dumpsys mention count or permission authorization alone is NOT proof of connection."
Write-Host "This check intentionally does not print raw dumpsys, logcat, chat text or payment details."
