# Open Margin setup engine: TCP port helpers.
# Windows PowerShell 5.1 compatible.

function Test-OmPortAvailable {
    param(
        [Parameter(Mandatory = $true)][int]$Port,
        [string]$BindAddress = '127.0.0.1',
        [int]$TimeoutMs = 300
    )
    if ($Port -lt 1 -or $Port -gt 65535) { return $false }
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $task = $client.ConnectAsync($BindAddress, $Port)
        if (-not $task.Wait($TimeoutMs)) { return $true }
        return -not $client.Connected
    } catch {
        # Connection refused/timeout means the port is free for our use.
        return $true
    } finally {
        try { $client.Close() } catch { }
    }
}

function Find-OmFreePort {
    param(
        [Parameter(Mandatory = $true)][int]$Start,
        [int]$End = ($Start + 20),
        [int[]]$IgnorePorts = @()
    )
    for ($p = $Start; $p -le $End; $p++) {
        if ($IgnorePorts -contains $p) { continue }
        if (Test-OmPortAvailable -Port $p) { return $p }
    }
    return $null
}

function Get-OmDefaultPorts {
    return [pscustomobject]@{
        api     = 8767
        wallos  = 18082
        gateway = 18768
        setup   = 18770
    }
}

function Get-OmPortOwner {
    param([Parameter(Mandatory = $true)][int]$Port)
    try {
        $connection = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction Stop | Select-Object -First 1
        if ($null -ne $connection) {
            $processName = $null
            try { $processName = (Get-Process -Id $connection.OwningProcess -ErrorAction Stop).ProcessName } catch { }
            return [pscustomobject]@{ port = $Port; pid = [int]$connection.OwningProcess; process = $processName }
        }
    } catch { }
    try {
        $lines = @(& netstat.exe -ano -p tcp 2>$null)
        foreach ($line in $lines) {
            if ($line -match ('^\s*TCP\s+\S+:' + $Port + '\s+\S+\s+LISTENING\s+(\d+)')) {
                $ownerPid = [int]$matches[1]
                $processName = $null
                try { $processName = (Get-Process -Id $ownerPid -ErrorAction Stop).ProcessName } catch { }
                return [pscustomobject]@{ port = $Port; pid = $ownerPid; process = $processName }
            }
        }
    } catch { }
    return $null
}

function Resolve-OmPorts {
    param(
        $Ports = (Get-OmDefaultPorts),
        [int[]]$IgnorePorts = @(),
        [switch]$AutoAdvance,
        [int]$SearchSpan = 20
    )
    $resolved = [pscustomobject]@{
        api     = [int]$Ports.api
        wallos  = [int]$Ports.wallos
        gateway = [int]$Ports.gateway
        setup   = [int]$Ports.setup
    }
    $conflicts = [System.Collections.Generic.List[object]]::new()
    $changes = [System.Collections.Generic.List[object]]::new()
    foreach ($name in @('api', 'wallos', 'gateway', 'setup')) {
        $port = [int]$resolved.$name
        if ($IgnorePorts -contains $port) { continue }
        if (-not (Test-OmPortAvailable -Port $port)) {
            if ($AutoAdvance) {
                $next = Find-OmFreePort -Start ($port + 1) -End ($port + $SearchSpan) -IgnorePorts $IgnorePorts
                if ($null -ne $next) {
                    $changes.Add([pscustomobject]@{ name = $name; from = $port; to = $next })
                    $resolved.$name = [int]$next
                    continue
                }
            }
            $owner = Get-OmPortOwner -Port $port
            $conflicts.Add([pscustomobject]@{
                name = $name; port = $port;
                pid = $(if ($owner) { $owner.pid } else { $null });
                process = $(if ($owner) { $owner.process } else { $null })
            })
        }
    }
    return [pscustomobject]@{
        ports = $resolved; conflicts = @($conflicts); changes = @($changes)
    }
}
