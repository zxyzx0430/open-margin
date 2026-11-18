# Open Margin Windows setup engine.
# Windows PowerShell 5.1 compatible. No external PowerShell modules required.

$script:OmSetupRoot = $PSScriptRoot
$script:OmRepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path

. (Join-Path $PSScriptRoot 'lib\Log.ps1')
. (Join-Path $PSScriptRoot 'lib\State.ps1')
. (Join-Path $PSScriptRoot 'lib\CredentialManager.ps1')
. (Join-Path $PSScriptRoot 'lib\Ports.ps1')
. (Join-Path $PSScriptRoot 'lib\Probes.ps1')
. (Join-Path $PSScriptRoot 'lib\Compose.ps1')
. (Join-Path $PSScriptRoot 'lib\OfflineBundle.ps1')
. (Join-Path $PSScriptRoot 'lib\Tailscale.ps1')
. (Join-Path $PSScriptRoot 'lib\Wallos.ps1')
. (Join-Path $PSScriptRoot 'lib\Pairing.ps1')
. (Join-Path $PSScriptRoot 'lib\Verify.ps1')
. (Join-Path $PSScriptRoot 'lib\Dependencies.ps1')
. (Join-Path $PSScriptRoot 'lib\Watchdog.ps1')
. (Join-Path $PSScriptRoot 'lib\Deploy.ps1')
. (Join-Path $PSScriptRoot 'lib\WebActions.ps1')

Export-ModuleMember -Function *
