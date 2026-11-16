# Open Margin setup engine: Wallos first-owner registration.

function Test-OmWallosOwner {
    param(
        [string]$ProjectName = 'personal-finance-system',
        [string]$Service = 'wallos'
    )
    $php = '$db=new SQLite3("/var/www/html/db/wallos.db");$n=$db->querySingle("SELECT COUNT(*) FROM user");echo $n;'
    $result = Invoke-OmDockerCompose -Arguments @('exec', '-T', $Service, 'php', '-r', $php) -ProjectName $ProjectName
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

function New-OmWallosOwner {
    param(
        [int]$WallosPort = 18082,
        [Parameter(Mandatory = $true)][string]$Username,
        [Parameter(Mandatory = $true)][string]$Password,
        [string]$Email = '',
        [string]$FirstName = '',
        [string]$LastName = 'Owner',
        [ValidateSet('en_US', 'zh_CN')][string]$Language = 'en_US'
    )
    if ($Password.Length -lt 8) {
        return [pscustomobject]@{ ok = $false; code = 'WEAK_PASSWORD'; message = '密码至少 8 位。' }
    }
    if ([string]::IsNullOrWhiteSpace($Email)) {
        $Email = 'openmargin+' + ([guid]::NewGuid().ToString('N').Substring(0, 8)) + '@example.com'
    }
    if ([string]::IsNullOrWhiteSpace($FirstName)) { $FirstName = $Username }
    $base = 'http://127.0.0.1:' + $WallosPort
    try {
        $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
        $null = Invoke-WebRequest -Uri ($base + '/registration.php') -WebSession $session -UseBasicParsing -TimeoutSec 20
        $form = @{
            username = $Username
            firstname = $FirstName
            lastname = $LastName
            email = $Email
            password = $Password
            confirm_password = $Password
            main_currency = 'CNY'
            language = $Language
        }
        try {
            $response = Invoke-WebRequest -Uri ($base + '/registration.php') -Method Post -Body $form -WebSession $session -UseBasicParsing -MaximumRedirection 0 -TimeoutSec 20
            $status = [int]$response.StatusCode
            $location = [string]$response.Headers['Location']
        } catch {
            $status = 0
            $location = ''
            if ($_.Exception.Response) {
                try { $status = [int]$_.Exception.Response.StatusCode } catch { }
                try { $location = [string]$_.Exception.Response.Headers['Location'] } catch { }
            }
        }
        if ($status -in @(301, 302, 303) -and $location -like '*login.php*') {
            return [pscustomobject]@{ ok = $true; code = 'OWNER_CREATED'; message = '管理员已创建。' }
        }
        return [pscustomobject]@{
            ok = $false; code = 'REGISTRATION_REJECTED';
            message = 'Wallos 注册表单没有接受这次提交。';
            status = $status; location = $location
        }
    } catch {
        return [pscustomobject]@{ ok = $false; code = 'REGISTRATION_FAILED'; message = $_.Exception.Message }
    }
}

function Wait-OmWallosOwner {
    param(
        [int]$TimeoutSec = 120,
        [string]$ProjectName = 'personal-finance-system'
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    do {
        $check = Test-OmWallosOwner -ProjectName $ProjectName
        if ($check.ok) { return $check }
        Start-Sleep -Seconds 3
    } while ((Get-Date) -lt $deadline)
    return [pscustomobject]@{ ok = $false; count = 0; output = 'timeout waiting for Wallos owner' }
}
