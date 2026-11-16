#Requires -Version 5.1
param(
    [int]$Port = 18770,
    [string]$StateRoot = '',
    [string]$RepoRoot = '',
    [switch]$NoBrowser,
    [switch]$Dev
)

$ErrorActionPreference = 'Stop'
$modulePath = Join-Path $PSScriptRoot 'OpenMargin.Setup.psd1'
Import-Module $modulePath -Force

if (-not $StateRoot) { $StateRoot = Join-Path $env:LOCALAPPDATA 'OpenMargin' }
if (-not $RepoRoot) { $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path }
Initialize-OmPaths -StateRoot $StateRoot -RepoRoot $RepoRoot

$webRoot = Join-Path $PSScriptRoot 'web'
$jobDir = Join-Path $StateRoot 'jobs'
if (-not (Test-Path $jobDir)) { New-Item -ItemType Directory -Force -Path $jobDir | Out-Null }
$setupToken = [Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Minimum 0 -Maximum 256 })) -replace '[^A-Za-z0-9]', ''
if ($setupToken.Length -lt 32) { $setupToken = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N') }

function Get-OmLocalOrigin {
    param([int]$P)
    return @('http://127.0.0.1:' + $P, 'http://localhost:' + $P)
}

function Test-OmRequestHost {
    param($Context, [int]$P)
    $hostName = [string]$Context.Request.UserHostName
    if ([string]::IsNullOrWhiteSpace($hostName)) { $hostName = [string]$Context.Request.Headers['Host'] }
    $hostOnly = (($hostName -split ':')[0]).Trim().ToLowerInvariant()
    return ($hostOnly -eq '127.0.0.1' -or $hostOnly -eq 'localhost')
}

function Test-OmRequestAuth {
    param($Context)
    if ($Dev) { return $true }
    $cookie = $Context.Request.Cookies['om_setup']
    if ($cookie -and $cookie.Value -eq $setupToken) { return $true }
    $header = $Context.Request.Headers['X-Om-Setup']
    return ($header -eq $setupToken)
}

function Test-OmPostOrigin {
    param($Context, [int]$P)
    $origin = [string]$Context.Request.Headers['Origin']
    if ([string]::IsNullOrWhiteSpace($origin)) { return $true }
    return ((Get-OmLocalOrigin $P) -contains $origin)
}

function Write-OmResponse {
    param(
        $Context,
        [int]$Status = 200,
        [string]$ContentType = 'application/json; charset=utf-8',
        [AllowNull()][byte[]]$Bytes = $null,
        [string]$Text = $null,
        [hashtable]$Headers = @{}
    )
    $response = $Context.Response
    $response.StatusCode = $Status
    $response.ContentType = $ContentType
    $response.Headers['Cache-Control'] = 'no-store'
    $response.Headers['X-Content-Type-Options'] = 'nosniff'
    $response.Headers['X-Frame-Options'] = 'DENY'
    foreach ($key in $Headers.Keys) { $response.Headers[$key] = $Headers[$key] }
    if ($null -eq $Bytes) {
        if ($null -eq $Text) { $Text = '' }
        $Bytes = [Text.Encoding]::UTF8.GetBytes($Text)
    }
    $response.ContentLength64 = $Bytes.Length
    $response.OutputStream.Write($Bytes, 0, $Bytes.Length)
    $response.OutputStream.Close()
}

function Write-OmJson {
    param($Context, $Value, [int]$Status = 200)
    $json = $Value | ConvertTo-Json -Depth 12
    Write-OmResponse -Context $Context -Status $Status -Text $json
}

function Write-OmStatic {
    param($Context, [string]$Relative)
    $allowed = @{
        'index.html' = 'text/html; charset=utf-8'
        'app.js' = 'application/javascript; charset=utf-8'
        'styles.css' = 'text/css; charset=utf-8'
        'qrcode.min.js' = 'application/javascript; charset=utf-8'
    }
    $name = [IO.Path]::GetFileName($Relative)
    if (-not $allowed.ContainsKey($name)) {
        Write-OmResponse -Context $Context -Status 404 -Text 'not found'
        return
    }
    $path = Join-Path $webRoot $name
    if (-not (Test-Path $path)) {
        Write-OmResponse -Context $Context -Status 404 -Text 'not found'
        return
    }
    $bytes = [IO.File]::ReadAllBytes($path)
    $headers = @{}
    if ($name -eq 'index.html') {
        $headers['Content-Security-Policy'] = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'"
    }
    Write-OmResponse -Context $Context -Status 200 -ContentType $allowed[$name] -Bytes $bytes -Headers $headers
}

function Read-OmRequestBody {
    param($Context)
    $reader = New-Object System.IO.StreamReader($Context.Request.InputStream, $Context.Request.ContentEncoding)
    try { $body = $reader.ReadToEnd() } finally { $reader.Close() }
    if ([string]::IsNullOrWhiteSpace($body)) { return [pscustomobject]@{} }
    try { return ($body | ConvertFrom-Json) } catch { throw 'invalid json body' }
}

function Set-OmJobStatus {
    param([string]$Path, [string]$Status)
    @{ status = $Status; updatedAt = (Get-Date).ToString('o') } | ConvertTo-Json | Set-Content -Path $Path -Encoding UTF8
}

function Start-OmWebJob {
    param([string]$Action, $Params)
    $id = [guid]::NewGuid().ToString('N')
    $paramsPath = Join-Path $jobDir ($id + '.params.json')
    $statusPath = Join-Path $jobDir ($id + '.status.json')
    $resultPath = Join-Path $jobDir ($id + '.result.json')
    $params | ConvertTo-Json -Depth 10 | Set-Content -Path $paramsPath -Encoding UTF8
    try {
        & icacls.exe $paramsPath /inheritance:r /grant:r ("$env:USERNAME:F") *> $null
    } catch { }
    Set-OmJobStatus -Path $statusPath -Status 'running'
    Start-Job -Name ('om-' + $id) -ScriptBlock {
        param($ModulePath, $ActionName, $ParamsPath, $StatusPath, $ResultPath, $StateRootArg, $RepoRootArg)
        $ErrorActionPreference = 'Stop'
        function Write-JobStatus([string]$s) {
            @{ status = $s; updatedAt = (Get-Date).ToString('o') } | ConvertTo-Json | Set-Content -Path $StatusPath -Encoding UTF8
        }
        try {
            Import-Module $ModulePath -Force
            Initialize-OmPaths -StateRoot $StateRootArg -RepoRoot $RepoRootArg
            Write-JobStatus 'running'
            $p = Get-Content -Path $ParamsPath -Raw -Encoding UTF8 | ConvertFrom-Json
            $state = Read-OmState
            $result = Invoke-OmWebAction -Action $ActionName -Params $p -Ports $state.ports -ProjectName 'personal-finance-system' -StateRoot $StateRootArg -RepoRoot $RepoRootArg
            $result | ConvertTo-Json -Depth 12 | Set-Content -Path $ResultPath -Encoding UTF8
            Write-JobStatus 'done'
        } catch {
            @{ ok = $false; code = 'JOB_ERROR'; message = $_.Exception.Message } |
                ConvertTo-Json -Depth 6 | Set-Content -Path $ResultPath -Encoding UTF8
            Write-JobStatus 'failed'
        } finally {
            Remove-Item -Force $ParamsPath -ErrorAction SilentlyContinue
        }
    } -ArgumentList $modulePath, $Action, $paramsPath, $statusPath, $resultPath, $StateRoot, $RepoRoot | Out-Null
    return $id
}

function Get-OmWebJob {
    param([string]$Id)
    if ($Id -notmatch '^[a-f0-9]{32}$') { return $null }
    $statusPath = Join-Path $jobDir ($Id + '.status.json')
    $resultPath = Join-Path $jobDir ($Id + '.result.json')
    $status = 'unknown'
    if (Test-Path $statusPath) {
        try { $status = (Get-Content -Path $statusPath -Raw -Encoding UTF8 | ConvertFrom-Json).status } catch { }
    } elseif (Test-Path $resultPath) {
        $status = 'done'
    }
    $result = $null
    if (Test-Path $resultPath) {
        try { $result = Get-Content -Path $resultPath -Raw -Encoding UTF8 | ConvertFrom-Json } catch { }
    }
    return [pscustomobject]@{ id = $Id; status = $status; result = $result }
}

function Clear-OmOldJobs {
    $cutoff = (Get-Date).AddHours(-24)
    Get-ChildItem -Path $jobDir -File -ErrorAction SilentlyContinue | Where-Object { $_.LastWriteTime -lt $cutoff } | Remove-Item -Force -ErrorAction SilentlyContinue
}

Clear-OmOldJobs

$listener = New-Object System.Net.HttpListener
$prefix = 'http://127.0.0.1:' + $Port + '/'
$listener.Prefixes.Add($prefix)
try {
    $listener.Start()
} catch {
    $listener.Close()
    Write-Error ("无法监听 $prefix。请以管理员运行一次：netsh http add urlacl url=$prefix user=$env:USERDOMAIN\$env:USERNAME 。原始错误：" + $_.Exception.Message)
    exit 1
}

$pidPath = Join-Path $StateRoot 'setup-web.json'
@{ port = $Port; pid = $PID; startedAt = (Get-Date).ToString('o') } | ConvertTo-Json | Set-Content -Path $pidPath -Encoding UTF8
$url = $prefix + '?token=' + [uri]::EscapeDataString($setupToken)
Write-Output ('Open Margin setup wizard: ' + $url)
if (-not $NoBrowser) {
    try { Start-Process $url | Out-Null } catch { }
}

try {
    while ($listener.IsListening) {
        $context = $listener.GetContext()
        try {
            if (-not (Test-OmRequestHost -Context $context -P $Port)) {
                Write-OmResponse -Context $context -Status 403 -Text 'forbidden host'
                continue
            }
            $path = $context.Request.Url.AbsolutePath
            $method = $context.Request.HttpMethod.ToUpperInvariant()

            if ($method -eq 'GET' -and ($path -eq '/' -or $path -eq '/index.html')) {
                if (-not $Dev) {
                    $token = [string]$context.Request.QueryString['token']
                    if ($token -and $token -eq $setupToken) {
                        $cookie = New-Object System.Net.Cookie('om_setup', $setupToken, '/', '127.0.0.1')
                        $context.Response.SetCookie($cookie)
                        $context.Response.Redirect('/')
                        $context.Response.Close()
                        continue
                    }
                }
                Write-OmStatic -Context $context -Relative 'index.html'
                continue
            }

            if ($method -eq 'GET' -and $path -in @('/app.js', '/styles.css', '/qrcode.min.js')) {
                Write-OmStatic -Context $context -Relative $path.TrimStart('/')
                continue
            }

            if (-not $path.StartsWith('/api/')) {
                Write-OmResponse -Context $context -Status 404 -Text 'not found'
                continue
            }

            if (-not (Test-OmRequestAuth -Context $context)) {
                Write-OmJson -Context $context -Value @{ ok = $false; code = 'UNAUTHORIZED'; message = '请从安装器打开向导。' } -Status 401
                continue
            }
            if ($method -eq 'POST' -and -not (Test-OmPostOrigin -Context $context -P $Port)) {
                Write-OmJson -Context $context -Value @{ ok = $false; code = 'BAD_ORIGIN'; message = 'origin rejected' } -Status 403
                continue
            }

            switch -Regex ($path) {
                '^/api/health$' {
                    Write-OmJson -Context $context -Value @{ ok = $true; service = 'open-margin-setup'; version = '1.2.5' }
                }
                '^/api/config$' {
                    $state = Read-OmState
                    Write-OmJson -Context $context -Value @{
                        ok = $true; version = '1.2.5'; repoRoot = $RepoRoot;
                        ports = $state.ports; webPort = $Port
                    }
                }
                '^/api/status$' {
                    $state = Read-OmState
                    $checks = @(Get-OmEnvironmentChecks -SkipNetwork -Ports $state.ports -RepoRoot $RepoRoot)
                    Write-OmJson -Context $context -Value @{
                        ok = $true; state = $state; checks = $checks;
                        summary = (Get-OmCheckSummary $checks); drift = @(Test-OmStateDrift $state)
                    }
                }
                '^/api/checks$' {
                    $state = Read-OmState
                    $skip = ([string]$context.Request.QueryString['network']) -ne '1'
                    $checks = @(Get-OmEnvironmentChecks -SkipNetwork:$skip -Ports $state.ports -RepoRoot $RepoRoot)
                    Write-OmJson -Context $context -Value @{ ok = (Get-OmCheckSummary $checks).ok; checks = $checks; summary = (Get-OmCheckSummary $checks) }
                }
                '^/api/logs$' {
                    $tail = 200
                    [int]::TryParse([string]$context.Request.QueryString['tail'], [ref]$tail) | Out-Null
                    if ($tail -lt 1 -or $tail -gt 1000) { $tail = 200 }
                    Write-OmJson -Context $context -Value @{ ok = $true; lines = @(Get-OmLogTail -Lines $tail) }
                }
                '^/api/qr$' {
                    $state = Read-OmState
                    $result = Invoke-OmWebAction -Action 'pair-qr' -Params ([pscustomobject]@{}) -Ports $state.ports -ProjectName 'personal-finance-system' -StateRoot $StateRoot -RepoRoot $RepoRoot
                    Write-OmJson -Context $context -Value $result
                }
                '^/api/jobs/([a-f0-9]{32})$' {
                    $id = $matches[1]
                    $job = Get-OmWebJob -Id $id
                    if ($null -eq $job) { Write-OmJson -Context $context -Value @{ ok = $false; code = 'NOT_FOUND' } -Status 404 }
                    else { Write-OmJson -Context $context -Value $job }
                }
                '^/api/actions/([a-z0-9-]+)$' {
                    if ($method -ne 'POST') {
                        Write-OmJson -Context $context -Value @{ ok = $false; code = 'METHOD_NOT_ALLOWED' } -Status 405
                        continue
                    }
                    $action = $matches[1]
                    try { $body = Read-OmRequestBody -Context $context } catch {
                        Write-OmJson -Context $context -Value @{ ok = $false; code = 'BAD_JSON'; message = $_.Exception.Message } -Status 400
                        continue
                    }
                    $id = Start-OmWebJob -Action $action -Params $body
                    Write-OmJson -Context $context -Value @{ ok = $true; jobId = $id; action = $action }
                }
                default {
                    Write-OmJson -Context $context -Value @{ ok = $false; code = 'NOT_FOUND' } -Status 404
                }
            }
        } catch {
            try { Write-OmJson -Context $context -Value @{ ok = $false; code = 'SERVER_ERROR'; message = $_.Exception.Message } -Status 500 } catch { }
        } finally {
            try { $context.Response.Close() } catch { }
        }
    }
} finally {
    try { $listener.Stop(); $listener.Close() } catch { }
    Remove-Item -Force $pidPath -ErrorAction SilentlyContinue
}
