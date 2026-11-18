# Runs unelevated under the original installing user while the setup server is
# elevated under a possibly different administrator account.
# The pipe carries one JSON request per connection and never writes to disk.
#Requires -Version 5.1
param(
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9_-]{16,80}$')][string]$PipeName,
    [Parameter(Mandatory = $true)][string]$OriginalUserSid,
    [ValidatePattern('^(OpenMargin/WallosLogin|OpenMargin\.Test\.[a-f0-9]{32})$')][string]$CredentialTarget = 'OpenMargin/WallosLogin'
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'CredentialManager.ps1')
$script:OmWallosCredentialTarget = $CredentialTarget

$ownerSid = New-Object System.Security.Principal.SecurityIdentifier($OriginalUserSid)
$adminsSid = New-Object System.Security.Principal.SecurityIdentifier([System.Security.Principal.WellKnownSidType]::BuiltinAdministratorsSid, $null)
$pipeSecurity = New-Object System.IO.Pipes.PipeSecurity
$pipeSecurity.SetAccessRuleProtection($true, $false)
$pipeSecurity.AddAccessRule((New-Object System.IO.Pipes.PipeAccessRule($ownerSid, [System.IO.Pipes.PipeAccessRights]::FullControl, [System.Security.AccessControl.AccessControlType]::Allow)))
$pipeSecurity.AddAccessRule((New-Object System.IO.Pipes.PipeAccessRule($adminsSid, [System.IO.Pipes.PipeAccessRights]::FullControl, [System.Security.AccessControl.AccessControlType]::Allow)))

function New-OmCredentialPipeServer {
    param([System.IO.Pipes.PipeSecurity]$Security, [string]$Name)
    try { Add-Type -AssemblyName System.IO.Pipes.AccessControl -ErrorAction Stop } catch { }
    $aclType = 'System.IO.Pipes.NamedPipeServerStreamAcl' -as [type]
    if ($aclType) {
        $method = $aclType.GetMethod('Create')
        $arguments = [object[]]@(
            $Name,
            [System.IO.Pipes.PipeDirection]::InOut,
            1,
            [System.IO.Pipes.PipeTransmissionMode]::Byte,
            [System.IO.Pipes.PipeOptions]::Asynchronous,
            0,
            0,
            $Security,
            [System.IO.HandleInheritability]::None,
            [Enum]::ToObject([System.IO.Pipes.PipeAccessRights], 0)
        )
        return $method.Invoke($null, $arguments)
    }
    return (New-Object System.IO.Pipes.NamedPipeServerStream(
        $Name,
        [System.IO.Pipes.PipeDirection]::InOut,
        1,
        [System.IO.Pipes.PipeTransmissionMode]::Byte,
        [System.IO.Pipes.PipeOptions]::Asynchronous,
        0,
        0,
        $Security,
        [System.IO.HandleInheritability]::None
    ))
}

$serverPid = 0
$idleBeforeAttach = (Get-Date).AddMinutes(15)
$stopped = $false
$utf8 = New-Object System.Text.UTF8Encoding($false)

while (-not $stopped) {
    $pipe = $null
    $reader = $null
    $writer = $null
    $line = $null
    $request = $null
    $username = $null
    $password = $null
    $response = $null
    try {
        $pipe = New-OmCredentialPipeServer -Security $pipeSecurity -Name $PipeName
        $connect = $pipe.WaitForConnectionAsync()
        while (-not $connect.IsCompleted) {
            if ($serverPid -gt 0 -and -not (Get-Process -Id $serverPid -ErrorAction SilentlyContinue)) { $stopped = $true; break }
            if ($serverPid -le 0 -and (Get-Date) -ge $idleBeforeAttach) { $stopped = $true; break }
            Start-Sleep -Milliseconds 250
        }
        if ($stopped) { continue }
        $connect.GetAwaiter().GetResult()
        $reader = New-Object System.IO.StreamReader($pipe, $utf8, $false, 1024, $true)
        $writer = New-Object System.IO.StreamWriter($pipe, $utf8, 1024, $true)
        $writer.AutoFlush = $true
        $line = $reader.ReadLine()
        if ([string]::IsNullOrWhiteSpace($line) -or $line.Length -gt 65536) {
            $response = [pscustomobject]@{ ok = $false; code = 'CREDENTIAL_REQUEST_INVALID' }
        } else {
            try {
                $request = $line | ConvertFrom-Json
                $command = [string]$request.command
                switch ($command) {
                    'attach' {
                        $candidatePid = 0
                        if (-not [int]::TryParse([string]$request.serverPid, [ref]$candidatePid) -or $candidatePid -le 0) {
                            $response = [pscustomobject]@{ ok = $false; code = 'CREDENTIAL_ATTACH_INVALID' }
                        } else {
                            $serverPid = $candidatePid
                            $response = [pscustomobject]@{ ok = $true; code = 'CREDENTIAL_BRIDGE_ATTACHED' }
                        }
                    }
                    'set' {
                        $username = [string]$request.username
                        $password = [string]$request.password
                        if ([string]::IsNullOrWhiteSpace($username) -or $password.Length -lt 8 -or $password.Length -gt 256) {
                            $response = [pscustomobject]@{ ok = $false; code = 'CREDENTIAL_INPUT_INVALID' }
                        } else {
                            $response = Save-OmCredentialDirect -Username $username -Password $password
                        }
                        $password = $null
                    }
                    'get' { $response = Get-OmCredentialDirect }
                    'delete' { $response = Remove-OmCredentialDirect }
                    'stop' {
                        $response = [pscustomobject]@{ ok = $true; code = 'CREDENTIAL_BRIDGE_STOPPING' }
                        $stopped = $true
                    }
                    default { $response = [pscustomobject]@{ ok = $false; code = 'CREDENTIAL_COMMAND_INVALID' } }
                }
            } catch {
                $response = [pscustomobject]@{ ok = $false; code = 'CREDENTIAL_REQUEST_INVALID' }
            }
        }
        $writer.WriteLine(($response | ConvertTo-Json -Depth 4 -Compress))
    } catch {
        if (-not $pipe) {
            [Console]::Error.WriteLine('Original-user credential bridge could not create its protected pipe.')
            exit 1
        }
        if ($serverPid -gt 0 -and -not (Get-Process -Id $serverPid -ErrorAction SilentlyContinue)) { $stopped = $true }
    } finally {
        if ($request -and $request.PSObject.Properties['password']) { $request.password = $null }
        if ($response -and $response.PSObject.Properties['password']) { $response.password = $null }
        $line = $null
        $request = $null
        $username = $null
        $password = $null
        $response = $null
        if ($reader) { try { $reader.Dispose() } catch { } }
        if ($writer) { try { $writer.Dispose() } catch { } }
        if ($pipe) { try { $pipe.Dispose() } catch { } }
    }
    if ($serverPid -gt 0 -and -not (Get-Process -Id $serverPid -ErrorAction SilentlyContinue)) { $stopped = $true }
}
