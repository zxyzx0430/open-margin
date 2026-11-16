# Open Margin Windows setup engine.
# Windows PowerShell 5.1 compatible. No external PowerShell modules required.

$script:OmSetupRoot = $PSScriptRoot
$script:OmRepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path

. (Join-Path $PSScriptRoot 'lib\Log.ps1')
. (Join-Path $PSScriptRoot 'lib\State.ps1')
. (Join-Path $PSScriptRoot 'lib\Ports.ps1')
. (Join-Path $PSScriptRoot 'lib\Probes.ps1')
. (Join-Path $PSScriptRoot 'lib\Compose.ps1')
. (Join-Path $PSScriptRoot 'lib\Tailscale.ps1')
. (Join-Path $PSScriptRoot 'lib\Wallos.ps1')
. (Join-Path $PSScriptRoot 'lib\Pairing.ps1')
. (Join-Path $PSScriptRoot 'lib\Verify.ps1')
. (Join-Path $PSScriptRoot 'lib\Dependencies.ps1')
. (Join-Path $PSScriptRoot 'lib\Watchdog.ps1')
. (Join-Path $PSScriptRoot 'lib\Deploy.ps1')
. (Join-Path $PSScriptRoot 'lib\WebActions.ps1')

Export-ModuleMember -Function @(
    'Initialize-OmPaths', 'Get-OmStateRoot', 'Get-OmStatePath', 'Read-OmState', 'Save-OmState',
    'Set-OmStateValue', 'Set-OmStep', 'Test-OmStateDrift', 'Enter-OmStateLock', 'Exit-OmStateLock',
    'Write-OmLog', 'Protect-OmText', 'Get-OmLogTail', 'Initialize-OmLog',
    'Test-OmPortAvailable', 'Find-OmFreePort', 'Get-OmDefaultPorts', 'Resolve-OmPorts',
    'Test-OmAdmin', 'Get-OmWindowsInfo', 'Test-OmVirtualization', 'Get-OmDiskInfo',
    'Get-OmCommandPath', 'Get-OmDockerInfo', 'Find-OmTailscale', 'Get-OmTailscaleInfo',
    'Get-OmNetworkChecks', 'Get-OmEnvironmentChecks', 'Get-OmCheckSummary',
    'Get-OmRepoRoot', 'Get-OmSecretsPath', 'Get-OmBackendEnvPath', 'Get-OmDeviceTokenFromFile',
    'Set-OmDeviceTokenInFile', 'New-OmSecretValue', 'New-OmSecrets',
    'Invoke-OmDockerCompose', 'Wait-OmDockerReady', 'Start-OmDockerDesktop', 'Wait-OmHttpOk',
    'Invoke-OmComposeUp', 'Invoke-OmComposeDown', 'Get-OmComposePs',
    'Get-OmTailscalePath', 'Invoke-OmTailscale', 'Get-OmTailscaleStatusJson', 'Get-OmRouteHandlers',
    'Test-OmOurProxy', 'Test-OmForeignRoutes', 'Test-OmFunnelReady', 'Test-OmServeOwnedByUs',
    'Enable-OmFunnel', 'Disable-OmFunnel', 'Get-OmFunnelUrl', 'Start-OmTailscaleLogin',
    'Test-OmWallosOwner', 'New-OmWallosOwner', 'Wait-OmWallosOwner',
    'Get-OmServerId', 'New-OmPairingPayload', 'Rotate-OmDeviceToken',
    'Get-OmHttpStatus', 'Invoke-OmVerify',
    'Install-OmDependencies', 'Install-OmWsl', 'Install-OmDockerDesktop', 'Install-OmTailscale',
    'Set-OmTailscaleOperator', 'Add-OmUrlAcl', 'Test-OmRebootPending', 'Invoke-OmElevatedScript',
    'Install-OmWatchdog', 'Uninstall-OmWatchdog', 'Set-OmWatchdogPaused',
    'Invoke-OmRemoteDeploy', 'Invoke-OmWebAction', 'Invoke-OmElevatedDependencyInstall'
)
