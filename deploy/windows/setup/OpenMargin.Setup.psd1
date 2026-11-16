@{
    RootModule        = 'OpenMargin.Setup.psm1'
    ModuleVersion     = '1.0.0'
    GUID              = '8f0f3f5e-2a9b-4f3e-9a1c-6c1f7a2d4e11'
    Author            = 'Open Margin'
    CompanyName       = 'Open Margin'
    Copyright         = '(c) Open Margin'
    Description       = 'Deterministic Windows setup engine for Open Margin / 清衡.'
    PowerShellVersion = '5.1'
    FunctionsToExport = @(
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
}
