# Open Margin setup engine: Wallos first-owner registration.
# Uses the official registration form only; never writes SQLite directly and
# never logs the administrator password.

function Test-OmWallosOwner {
    param(
        [string]$ProjectName = 'personal-finance-system',
        [string]$RepoRoot = (Get-OmRepoRoot),
        [switch]$DryRun
    )
    if ($DryRun) {
        return [pscustomobject]@{ ok = $true; count = 1; output = 'DRY RUN: owner assumed present' }
    }
    # Windows PowerShell 5.1 strips double quotes from native arguments, so the
    # PHP snippet uses single quotes only; otherwise php -r receives a broken
    # SQLite3(...) call and the owner check never succeeds.
    $php = '$db=new SQLite3(''/var/www/html/db/wallos.db'');$n=$db->querySingle(''SELECT COUNT(*) FROM user'');echo $n;'
    $result = Invoke-OmDockerCompose -Arguments @('exec', '-T', 'wallos', 'php', '-r', $php) -ProjectName $ProjectName -RepoRoot $RepoRoot
    if ($result.exitCode -ne 0) {
        return [pscustomobject]@{ ok = $false; count = $null; output = Protect-OmText $result.output }
    }
    $text = ([string]$result.output).Trim()
    $count = 0
    if (-not [int]::TryParse($text, [ref]$count)) {
        return [pscustomobject]@{ ok = $false; count = $null; output = Protect-OmText $text }
    }
    return [pscustomobject]@{ ok = ($count -ge 1); count = $count; output = $null }
}

function Get-OmWallosManualSteps {
    param([int]$WallosPort = 18082)
    return [pscustomobject]@{
        url = 'http://127.0.0.1:' + $WallosPort + '/registration.php'
        steps = @(
            '打开 Wallos 注册页。',
            '填写用户名、密码和邮箱，货币选 CNY。',
            '语言先选 English (en_US)，完成后再到设置里改中文。',
            '提交后回到向导点“重试”，向导会自动检测管理员是否创建成功。'
        )
    }
}

function New-OmWallosOwner {
    param(
        [int]$WallosPort = 18082,
        [Parameter(Mandatory = $true)][string]$Username,
        [Parameter(Mandatory = $true)][string]$Password,
        [string]$Email = '',
        [string]$FirstName = '',
        [string]$LastName = 'Owner',
        [ValidateSet('en_US', 'zh_CN')][string]$Language = 'en_US',
        [switch]$DryRun
    )
    if ($Password.Length -lt 8) {
        return [pscustomobject]@{ ok = $false; code = 'WEAK_PASSWORD'; message = '密码至少 8 位。'; retryable = $true }
    }
    if ([string]::IsNullOrWhiteSpace($Email)) {
        $Email = 'openmargin+' + ([guid]::NewGuid().ToString('N').Substring(0, 8)) + '@example.com'
    }
    if ([string]::IsNullOrWhiteSpace($FirstName)) { $FirstName = $Username }
    if ($DryRun) {
        return [pscustomobject]@{ ok = $true; code = 'DRY_RUN'; message = 'DRY RUN: owner registration skipped' }
    }
    $base = 'http://127.0.0.1:' + $WallosPort
    $handler = $null
    $client = $null
    try {
        # Windows PowerShell 5.1 does not load System.Net.Http by default.
        try { Add-Type -AssemblyName System.Net.Http -ErrorAction Stop } catch { }
        $handler = New-Object System.Net.Http.HttpClientHandler
        $handler.AllowAutoRedirect = $false
        $handler.UseCookies = $true
        $handler.CookieContainer = New-Object System.Net.CookieContainer
        $client = New-Object -TypeName System.Net.Http.HttpClient -ArgumentList $handler
        $client.Timeout = [TimeSpan]::FromSeconds(30)

        $registration = $client.GetAsync($base + '/registration.php').GetAwaiter().GetResult()
        $registrationStatus = [int]$registration.StatusCode
        if ($registrationStatus -ne 200) {
            return [pscustomobject]@{
                ok = $false; code = 'REGISTRATION_PAGE_UNAVAILABLE';
                message = '无法打开 Wallos 注册页。'; status = $registrationStatus; retryable = $true
            }
        }

        $form = [ordered]@{
            username         = $Username
            firstname        = $FirstName
            lastname         = $LastName
            email            = $Email
            password         = $Password
            confirm_password = $Password
            main_currency    = 'CNY'
            language         = $Language
        }
        $pairs = [System.Collections.Generic.List[string]]::new()
        foreach ($key in $form.Keys) {
            $pairs.Add(([System.Net.WebUtility]::UrlEncode([string]$key) + '=' + [System.Net.WebUtility]::UrlEncode([string]$form[$key])))
        }
        $body = $pairs -join '&'
        $content = New-Object -TypeName System.Net.Http.StringContent -ArgumentList @($body, [System.Text.Encoding]::UTF8, 'application/x-www-form-urlencoded')
        $response = $client.PostAsync($base + '/registration.php', $content).GetAwaiter().GetResult()
        $status = [int]$response.StatusCode
        $location = ''
        if ($null -ne $response.Headers.Location) { $location = [string]$response.Headers.Location }
        if ($status -in @(301, 302, 303) -and $location -like '*login.php*') {
            return [pscustomobject]@{ ok = $true; code = 'OWNER_CREATED'; message = '管理员已创建。' }
        }
        return [pscustomobject]@{
            ok = $false; code = 'REGISTRATION_REJECTED';
            message = 'Wallos 注册表单没有接受这次提交。可以手动完成注册。';
            status = $status; location = $location; retryable = $true;
            manual = (Get-OmWallosManualSteps -WallosPort $WallosPort)
        }
    } catch {
        return [pscustomobject]@{
            ok = $false; code = 'REGISTRATION_FAILED';
            message = '自动注册管理员失败，可以手动完成。'; detail = $_.Exception.Message;
            retryable = $true; manual = (Get-OmWallosManualSteps -WallosPort $WallosPort)
        }
    } finally {
        if ($client) { try { $client.Dispose() } catch { } }
        if ($handler) { try { $handler.Dispose() } catch { } }
    }
}

function Wait-OmWallosOwner {
    param(
        [int]$TimeoutSec = 120,
        [int]$PollSeconds = 3,
        [string]$ProjectName = 'personal-finance-system',
        [string]$RepoRoot = (Get-OmRepoRoot),
        [switch]$DryRun
    )
    if ($DryRun) {
        return [pscustomobject]@{ ok = $true; count = 1; output = 'DRY RUN' }
    }
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    do {
        $check = Test-OmWallosOwner -ProjectName $ProjectName -RepoRoot $RepoRoot
        if ($check.ok) { return $check }
        Start-Sleep -Seconds $PollSeconds
    } while ((Get-Date) -lt $deadline)
    return [pscustomobject]@{ ok = $false; count = 0; output = 'timeout waiting for Wallos owner'; retryable = $true }
}
