# Open Margin setup engine: TCP port helpers.

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
        return $true
    } finally {
        try { $client.Close() } catch { }
    }
}

function Find-OmFreePort {
    param(
        [Parameter(Mandatory = $true)][int]$Start,
        [int]$End = ($Start + 20)
    )
    for ($p = $Start; $p -le $End; $p++) {
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

function Resolve-OmPorts {
    param($Ports = (Get-OmDefaultPorts))
    $resolved = [pscustomobject]@{
        api     = $Ports.api
        wallos  = $Ports.wallos
        gateway = $Ports.gateway
        setup   = $Ports.setup
    }
    $conflicts = @()
    foreach ($name in @('api', 'wallos', 'gateway', 'setup')) {
        $port = [int]$resolved.$name
        if (-not (Test-OmPortAvailable -Port $port)) {
            $conflicts += [pscustomobject]@{ name = $name; port = $port }
        }
    }
    return [pscustomobject]@{ ports = $resolved; conflicts = $conflicts }
}
