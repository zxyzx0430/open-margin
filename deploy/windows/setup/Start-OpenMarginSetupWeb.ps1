#Requires -Version 5.1
param(
    [int]$Port = 18770,
    [string]$StateRoot = '',
    [string]$RepoRoot = '',
    [switch]$NoBrowser,
    [switch]$Dev,
    [switch]$DryRun,
    [switch]$Resume
)

$ErrorActionPreference = 'Stop'
$modulePath = Join-Path $PSScriptRoot 'OpenMargin.Setup.psd1'
Import-Module $modulePath -Force -DisableNameChecking

if (-not $StateRoot) { $StateRoot = Join-Path $env:LOCALAPPDATA 'OpenMargin' }
if (-not $RepoRoot) { $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path }
Initialize-OmPaths -StateRoot $StateRoot -RepoRoot $RepoRoot
if ($Resume) {
    $null = Unregister-OmResumeRunOnce
    Write-OmLog -Level INFO -Message 'resumed after reboot'
}

$webRoot = Join-Path $PSScriptRoot 'web'
$jobDir = Join-Path $StateRoot 'jobs'
if (-not (Test-Path $jobDir)) { New-Item -ItemType Directory -Force -Path $jobDir | Out-Null }

# Cryptographically strong one-time setup token. It is placed in a SameSite
# Strict, HttpOnly cookie so a cross-site POST cannot ride an existing cookie.
$tokenBytes = New-Object byte[] 32
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($tokenBytes) } finally { $rng.Dispose() }
$setupToken = [Convert]::ToBase64String($tokenBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')

function Get-OmLocalOrigins {
    param([int]$P)
    # Parentheses matter: PowerShell's comma operator binds tighter than +.
    return @(('http://127.0.0.1:' + $P), ('http://localhost:' + $P))
}

function Test-OmRequestHost {
    param($Context, [int]$P)
    $hostHeader = [string]$Context.Request.Headers['Host']
    if ([string]::IsNullOrWhiteSpace($hostHeader)) { return $false }
    $parts = $hostHeader.Split(':')
    $hostOnly = $parts[0].Trim().ToLowerInvariant()
    if ($hostOnly -ne '127.0.0.1' -and $hostOnly -ne 'localhost') { return $false }
    if ($parts.Length -gt 1) {
        $portValue = 0
        if (-not [int]::TryParse($parts[1], [ref]$portValue)) { return $false }
        return ($portValue -eq $P)
    }
    return ($P -eq 80)
}

function Test-OmRequestAuth {
    param($Context)
    if ($Dev) { return $true }
    $cookie = $Context.Request.Cookies['om_setup']
    if ($cookie -and $cookie.Value -eq $setupToken) { return $true }
    $header = [string]$Context.Request.Headers['X-Om-Setup']
    return ($header -eq $setupToken)
}

function Test-OmPostOrigin {
    param($Context, [int]$P)
    if ($Dev) { return $true }
    $origin = [string]$Context.Request.Headers['Origin']
    $header = [string]$Context.Request.Headers['X-Om-Setup']
    if ([string]::IsNullOrWhiteSpace($origin)) {
        # Non-browser clients must prove knowledge of the setup token; a
        # browser cross-site form post cannot add this header.
        return ($header -and $header -eq $setupToken)
    }
    return ((Get-OmLocalOrigins $P) -contains $origin)
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
    $response.Headers['Referrer-Policy'] = 'no-referrer'
    foreach ($key in $Headers.Keys) { $response.Headers[$key] = $Headers[$key] }
    if ($null -eq $Bytes) {
        if ($null -eq $Text) { $Text = '' }
        $Bytes = [Text.Encoding]::UTF8.GetBytes($Text)
    }
    $response.ContentLength64 = $Bytes.Length
    if ($Context.Request.HttpMethod -ne 'HEAD') {
        $response.OutputStream.Write($Bytes, 0, $Bytes.Length)
    }
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
        'index.html'   = 'text/html; charset=utf-8'
        'app.js'       = 'application/javascript; charset=utf-8'
        'styles.css'   = 'text/css; charset=utf-8'
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
        $headers['Content-Security-Policy'] = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'"
    }
    Write-OmResponse -Context $Context -Status 200 -ContentType $allowed[$name] -Bytes $bytes -Headers $headers
}

function Read-OmRequestBody {
    param($Context)
    if ($Context.Request.ContentLength64 -gt 65536) { throw 'request body too large' }
    $reader = New-Object System.IO.StreamReader($Context.Request.InputStream, $Context.Request.ContentEncoding)
    try { $body = $reader.ReadToEnd() } finally { $reader.Close() }
    if ([string]::IsNullOrWhiteSpace($body)) { return [pscustomobject]@{} }
    try { return ($body | ConvertFrom-Json) } catch { throw 'invalid json body' }
}

function Write-OmJsonFileAtomic {
    param([string]$Path, [string]$Json)
    $dir = Split-Path -Parent $Path
    if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    $attempts = 5
    for ($attempt = 1; $attempt -le $attempts; $attempt++) {
        $tmp = $Path + '.tmp-' + $PID + '-' + [guid]::NewGuid().ToString('N')
        try {
            [IO.File]::WriteAllText($tmp, $Json, $utf8)
            if ([IO.File]::Exists($Path)) {
                # Move-Item -Force does not reliably replace a locked/existing
                # target under Windows PowerShell 5.1; Replace/Copy is atomic
                # enough for status and result files.
                try {
                    [IO.File]::Replace($tmp, $Path, $null)
                } catch {
                    [IO.File]::Copy($tmp, $Path, $true)
                    [IO.File]::Delete($tmp)
                }
            } else {
                [IO.File]::Move($tmp, $Path)
            }
            return
        } catch {
            try { if ([IO.File]::Exists($tmp)) { [IO.File]::Delete($tmp) } } catch { }
            if ($attempt -lt $attempts) { Start-Sleep -Milliseconds (100 * $attempt) }
        }
    }
    throw ('could not write ' + $Path)
}

function Write-OmAtomicJson {
    param([string]$Path, $Value)
    $json = $Value | ConvertTo-Json -Depth 12
    Write-OmJsonFileAtomic -Path $Path -Json $json
}

function Set-OmJobStatus {
    param([string]$Path, [string]$Status)
    Write-OmAtomicJson -Path $Path -Value ([pscustomobject]@{ status = $Status; updatedAt = (Get-Date).ToString('o') })
}

# Fixed action whitelist: unknown actions are rejected before a job is created.
$allowedWebActions = @(
    'check', 'install-deps', 'deploy', 'create-owner', 'login-tailscale',
    'approve-funnel', 'verify', 'disable', 'enable', 'repair', 'backup',
    'pair-qr', 'rotate-token', 'pause-watchdog', 'resume-watchdog',
    'install-watchdog', 'uninstall-watchdog', 'watchdog-status',
    'set-public-probe-mode', 'phone-status',
    'select-offline-file', 'import-offline'
)

function Start-OmWebJob {
    param([string]$Action, $Params)
    $id = [guid]::NewGuid().ToString('N')
    $paramsPath = Join-Path $jobDir ($id + '.params.json')
    $statusPath = Join-Path $jobDir ($id + '.status.json')
    $resultPath = Join-Path $jobDir ($id + '.result.json')
    $progressPath = Join-Path $jobDir ($id + '.progress.json')
    Write-OmAtomicJson -Path $paramsPath -Value $Params
    try {
        & icacls.exe $paramsPath /inheritance:r /grant:r ("$env:USERNAME:F") *> $null
    } catch { }
    Set-OmJobStatus -Path $statusPath -Status 'running'
    Start-Job -Name ('om-' + $id) -ScriptBlock {
        param($ModulePath, $ActionName, $ParamsPath, $StatusPath, $ResultPath, $StateRootArg, $RepoRootArg, $SetupPortArg, $DryRunArg, $JobIdArg)
        $ErrorActionPreference = 'Stop'
        $jobUtf8 = New-Object System.Text.UTF8Encoding($false)
        function Write-JobFile([string]$Path, [string]$Text) {
            $attempts = 5
            for ($attempt = 1; $attempt -le $attempts; $attempt++) {
                $tmp = $Path + '.tmp-' + $PID + '-' + [guid]::NewGuid().ToString('N')
                try {
                    [IO.File]::WriteAllText($tmp, $Text, $jobUtf8)
                    if ([IO.File]::Exists($Path)) {
                        try {
                            [IO.File]::Replace($tmp, $Path, $null)
                        } catch {
                            [IO.File]::Copy($tmp, $Path, $true)
                            [IO.File]::Delete($tmp)
                        }
                    } else {
                        [IO.File]::Move($tmp, $Path)
                    }
                    return
                } catch {
                    try { if ([IO.File]::Exists($tmp)) { [IO.File]::Delete($tmp) } } catch { }
                    if ($attempt -lt $attempts) { Start-Sleep -Milliseconds (100 * $attempt) }
                }
            }
            throw ('could not write ' + $Path)
        }
        function Write-JobStatus([string]$s) {
            $payload = [pscustomobject]@{ status = $s; updatedAt = (Get-Date).ToString('o') }
            Write-JobFile -Path $StatusPath -Text ($payload | ConvertTo-Json)
        }
        try {
            Import-Module $ModulePath -Force -DisableNameChecking
            Initialize-OmPaths -StateRoot $StateRootArg -RepoRoot $RepoRootArg
            Write-JobStatus 'running'
            $p = Get-Content -Path $ParamsPath -Raw -Encoding UTF8 | ConvertFrom-Json
            if ($null -eq $p) { $p = [pscustomobject]@{} }
            $p | Add-Member -NotePropertyName setupPort -NotePropertyValue ([int]$SetupPortArg) -Force
            if ($DryRunArg) { $p | Add-Member -NotePropertyName dryRun -NotePropertyValue $true -Force }
            $state = Read-OmState
            $result = Invoke-OmWebAction -Action $ActionName -Params $p -Ports $state.ports -ProjectName 'personal-finance-system' -StateRoot $StateRootArg -RepoRoot $RepoRootArg -JobId $JobIdArg
            $payload = $result | ConvertTo-Json -Depth 12
            Write-JobFile -Path $ResultPath -Text $payload
            Write-JobStatus 'done'
        } catch {
            $message = [string]$_.Exception.Message
            $message = $message -replace '(?i)(token|password|secret)\s*[=:]\s*\S+', '$1=***'
            $message = $message -replace '\b[A-Za-z0-9_-]{40,}\b', '***'
            $errorPayload = [pscustomobject]@{ ok = $false; code = 'JOB_ERROR'; message = $message }
            try {
                Write-JobFile -Path $ResultPath -Text ($errorPayload | ConvertTo-Json -Depth 6)
            } catch { }
            Write-JobStatus 'failed'
        } finally {
            Remove-Item -Force $ParamsPath -ErrorAction SilentlyContinue
        }
    } -ArgumentList $modulePath, $Action, $paramsPath, $statusPath, $resultPath, $StateRoot, $RepoRoot, $Port, ([bool]$DryRun), $id | Out-Null
    return $id
}

function Get-OmWebJob {
    param([string]$Id)
    if ($Id -notmatch '^[a-f0-9]{32}$') { return $null }
    $statusPath = Join-Path $jobDir ($Id + '.status.json')
    $resultPath = Join-Path $jobDir ($Id + '.result.json')
    $progressPath = Join-Path $jobDir ($Id + '.progress.json')
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
    $progress = $null
    if (Test-Path $progressPath) {
        try { $progress = Get-Content -Path $progressPath -Raw -Encoding UTF8 | ConvertFrom-Json } catch { }
    }
    if ($null -eq $progress) {
        try {
            $state = Read-OmState
            if ($state.progress) { $progress = $state.progress }
        } catch { }
    }
    return [pscustomobject]@{ id = $Id; status = $status; result = $result; progress = $progress }
}

function Clear-OmOldJobs {
    $cutoff = (Get-Date).AddHours(-24)
    Get-ChildItem -Path $jobDir -File -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTime -lt $cutoff } |
        Remove-Item -Force -ErrorAction SilentlyContinue
    # Password-bearing parameter files must never survive a crashed job.
    $staleParamsCutoff = (Get-Date).AddHours(-1)
    Get-ChildItem -Path $jobDir -Filter '*.params.json' -File -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTime -lt $staleParamsCutoff } |
        Remove-Item -Force -ErrorAction SilentlyContinue
}

Clear-OmOldJobs

# Bind the first free port in the requested range. The wizard's own port is
# excluded from environment port checks by passing setupPort to every job.
$listener = $null
$boundPort = $null
$bindError = $null
foreach ($candidate in ($Port..($Port + 10))) {
    $probe = New-Object System.Net.HttpListener
    $probe.Prefixes.Add('http://127.0.0.1:' + $candidate + '/')
    try {
        $probe.Start()
        $listener = $probe
        $boundPort = $candidate
        break
    } catch {
        $bindError = $_.Exception.Message
        try { $probe.Close() } catch { }
    }
}
if ($null -eq $listener) {
    Write-Error ("无法监听 127.0.0.1:" + $Port + "-" + ($Port + 10) + "。请以管理员运行一次：netsh http add urlacl url=http://127.0.0.1:" + $Port + "/ user=" + $env:USERDOMAIN + "\" + $env:USERNAME + " 。原始错误：" + $bindError)
    exit 1
}
$Port = $boundPort
$prefix = 'http://127.0.0.1:' + $Port + '/'

$pidPath = Join-Path $StateRoot 'setup-web.json'
Write-OmAtomicJson -Path $pidPath -Value ([pscustomobject]@{
    port = $Port; pid = $PID; startedAt = (Get-Date).ToString('o');
    dev = [bool]$Dev; dryRun = [bool]$DryRun; url = ('http://127.0.0.1:' + $Port + '/')
})
$url = $prefix + '?token=' + [uri]::EscapeDataString($setupToken)
# One-time handoff for an elevated wizard process: Launch-Setup reads this
# loopback URL and opens the browser through explorer.exe (original user).
# The file is ACL-restricted and removed when the server exits.
$launchUrlPath = Join-Path $StateRoot 'setup-web-url.txt'
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($launchUrlPath, $url, $utf8NoBom)
try {
    $currentUser = [Security.Principal.WindowsIdentity]::GetCurrent().Name
    & icacls.exe $launchUrlPath /inheritance:r /grant:r ($currentUser + ':M') '*S-1-5-32-544:(F)' '*S-1-5-18:(F)' *> $null
} catch { }
Write-Output ('Open Margin setup wizard: ' + $url)
Write-OmLog -Level INFO -Message ('setup server elevated=' + [bool](Test-OmAdmin) + ' port=' + $Port + ' stateRoot=' + $StateRoot)
if (-not $NoBrowser) {
    # Do not swallow a browser-launch failure: the token URL is the only way
    # into the wizard, so keep setup-web-url.txt and print it for the caller.
    try {
        Start-Process $url -ErrorAction Stop | Out-Null
    } catch {
        Write-OmLog -Level WARN -Message ('browser open failed: ' + $_.Exception.Message)
        Write-Output ('Browser did not open automatically. Open this URL manually: ' + $url)
        Write-Output ('The URL is also saved at: ' + $launchUrlPath)
    }
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
                        $cookie.HttpOnly = $true
                        try { $cookie.SameSite = [System.Net.SameSiteMode]::Strict } catch { }
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

            if ($path -ne '/api/health' -and -not (Test-OmRequestAuth -Context $context)) {
                Write-OmJson -Context $context -Value @{ ok = $false; code = 'UNAUTHORIZED'; message = '请从安装器打开的页面进入向导。' } -Status 401
                continue
            }
            if ($method -eq 'POST' -and -not (Test-OmPostOrigin -Context $context -P $Port)) {
                Write-OmJson -Context $context -Value @{ ok = $false; code = 'BAD_ORIGIN'; message = 'origin rejected' } -Status 403
                continue
            }

            switch -Regex ($path) {
                '^/api/health$' {
                    if ($method -ne 'GET') {
                        Write-OmJson -Context $context -Value @{ ok = $false; code = 'METHOD_NOT_ALLOWED' } -Status 405
                        break
                    }
                    Write-OmJson -Context $context -Value @{ ok = $true; service = 'open-margin-setup'; version = '1.3.0'; dryRun = [bool]$DryRun }
                }
                '^/api/config$' {
                    if ($method -ne 'GET') {
                        Write-OmJson -Context $context -Value @{ ok = $false; code = 'METHOD_NOT_ALLOWED' } -Status 405
                        break
                    }
                    $state = Read-OmState
                    Write-OmJson -Context $context -Value @{
                        ok = $true; version = [string]$state.engineVersion; repoRoot = $RepoRoot;
                        ports = $state.ports; webPort = $Port; dev = [bool]$Dev; dryRun = [bool]$DryRun;
                        elevated = [bool](Test-OmAdmin);
                        dockerLicenseAcknowledged = (Test-OmDockerLicenseAcknowledged);
                        offline = (Get-OmOfflineBundleInfo -Version ([string]$state.engineVersion))
                    }
                }
                '^/api/status$' {
                    if ($method -ne 'GET') {
                        Write-OmJson -Context $context -Value @{ ok = $false; code = 'METHOD_NOT_ALLOWED' } -Status 405
                        break
                    }
                    $state = Read-OmState
                    $payload = [ordered]@{
                        ok = $true; state = $state; drift = @(Test-OmStateDrift $state);
                        setupPort = $Port; webPort = $Port
                    }
                    if ([string]$context.Request.QueryString['checks'] -eq '1') {
                        $checks = @(Get-OmEnvironmentChecks -SkipNetwork -Ports $state.ports -RepoRoot $RepoRoot -IgnorePorts @($Port))
                        $payload.checks = $checks
                        $payload.summary = (Get-OmCheckSummary $checks)
                    }
                    if ([string]$context.Request.QueryString['full'] -eq '1') {
                        $payload.watchdog = Invoke-OmWatchdogStatus -StateRoot $StateRoot
                    }
                    Write-OmJson -Context $context -Value $payload
                }
                '^/api/checks$' {
                    if ($method -ne 'GET') {
                        Write-OmJson -Context $context -Value @{ ok = $false; code = 'METHOD_NOT_ALLOWED' } -Status 405
                        break
                    }
                    $state = Read-OmState
                    $skip = ([string]$context.Request.QueryString['network']) -ne '1'
                    $checks = @(Get-OmEnvironmentChecks -SkipNetwork:$skip -Ports $state.ports -RepoRoot $RepoRoot -IgnorePorts @($Port))
                    $summary = Get-OmCheckSummary $checks
                    Write-OmJson -Context $context -Value @{ ok = $summary.ok; checks = $checks; summary = $summary }
                }
                '^/api/logs$' {
                    if ($method -ne 'GET') {
                        Write-OmJson -Context $context -Value @{ ok = $false; code = 'METHOD_NOT_ALLOWED' } -Status 405
                        break
                    }
                    $tail = 200
                    [int]::TryParse([string]$context.Request.QueryString['tail'], [ref]$tail) | Out-Null
                    if ($tail -lt 1 -or $tail -gt 1000) { $tail = 200 }
                    Write-OmJson -Context $context -Value @{ ok = $true; lines = @(Get-OmLogTail -Lines $tail) }
                }
                '^/api/qr$' {
                    if ($method -ne 'GET') {
                        Write-OmJson -Context $context -Value @{ ok = $false; code = 'METHOD_NOT_ALLOWED' } -Status 405
                        break
                    }
                    $state = Read-OmState
                    $result = Invoke-OmWebAction -Action 'pair-qr' -Params ([pscustomobject]@{}) -Ports $state.ports -ProjectName 'personal-finance-system' -StateRoot $StateRoot -RepoRoot $RepoRoot -DryRun:$DryRun
                    Write-OmJson -Context $context -Value $result
                }
                '^/api/jobs/([a-f0-9]{32})$' {
                    if ($method -ne 'GET') {
                        Write-OmJson -Context $context -Value @{ ok = $false; code = 'METHOD_NOT_ALLOWED' } -Status 405
                        break
                    }
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
                    if ($allowedWebActions -notcontains $action) {
                        Write-OmJson -Context $context -Value @{ ok = $false; code = 'NOT_FOUND' } -Status 404
                        continue
                    }
                    try { $body = Read-OmRequestBody -Context $context } catch {
                        Write-OmJson -Context $context -Value @{ ok = $false; code = 'BAD_JSON'; message = $_.Exception.Message } -Status 400
                        continue
                    }
                    if ($DryRun) { $body | Add-Member -NotePropertyName dryRun -NotePropertyValue $true -Force }
                    if ($action -eq 'select-offline-file') {
                        # A file picker needs an STA interactive desktop. Run it
                        # inline; this endpoint stays loopback-only and behind
                        # the setup token, and never accepts a file body.
                        if ($DryRun) {
                            Write-OmJson -Context $context -Value ([pscustomobject]@{
                                ok = $false; code = 'PICKER_UNAVAILABLE';
                                message = '演练模式不会打开文件选择框，请手动输入路径。'; retryable = $true
                            })
                            continue
                        }
                        $selection = Select-OmOfflineBundleFile
                        Write-OmJson -Context $context -Value $selection
                        continue
                    }
                    $id = Start-OmWebJob -Action $action -Params $body
                    Write-OmJson -Context $context -Value @{ ok = $true; jobId = $id; action = $action; dryRun = [bool]$DryRun }
                }
                default {
                    Write-OmJson -Context $context -Value @{ ok = $false; code = 'NOT_FOUND' } -Status 404
                }
            }
        } catch {
            try { Write-OmJson -Context $context -Value @{ ok = $false; code = 'SERVER_ERROR'; message = (Protect-OmText $_.Exception.Message) } -Status 500 } catch { }
        } finally {
            try { $context.Response.Close() } catch { }
        }
    }
} finally {
    try { $listener.Stop(); $listener.Close() } catch { }
    Remove-Item -Force $pidPath -ErrorAction SilentlyContinue
    Remove-Item -Force $launchUrlPath -ErrorAction SilentlyContinue
}
