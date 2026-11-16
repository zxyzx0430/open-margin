# Open Margin setup engine: logging and redaction.
# Windows PowerShell 5.1 compatible. Never writes device tokens, pairing
# payloads or administrator passwords to disk.

$script:OmLogPath = $null
$script:OmLogMaxBytes = 5MB

function Initialize-OmLog {
    param([Parameter(Mandatory = $true)][string]$Directory)
    if (-not (Test-Path $Directory)) {
        New-Item -ItemType Directory -Force -Path $Directory | Out-Null
    }
    $script:OmLogPath = Join-Path $Directory ("setup-{0}.log" -f (Get-Date -Format 'yyyyMMdd'))
    if ((Test-Path $script:OmLogPath) -and ((Get-Item $script:OmLogPath).Length -gt $script:OmLogMaxBytes)) {
        Move-Item -Force $script:OmLogPath ($script:OmLogPath + '.1')
    }
}

function Protect-OmText {
    param([AllowNull()][string]$Text)
    if ([string]::IsNullOrEmpty($Text)) { return $Text }
    $protected = $Text
    # Mask header and bearer forms before the generic key/value rule.
    $protected = [regex]::Replace($protected, '(?i)(Authorization\s*:\s*Bearer\s+)\S+', '$1***')
    $protected = [regex]::Replace($protected, '(?i)(Authorization\s*:\s*)(?!Bearer\b)\S+', '$1***')
    $protected = [regex]::Replace($protected, '(?i)(X-(?:Device|Admin|Wallos-Ledger)-Token\s*:\s*)\S+', '$1***')
    $protected = [regex]::Replace($protected, '(?i)Bearer\s+[A-Za-z0-9._~+/=-]+', 'Bearer ***')
    # Key/value forms, including JSON: "token": "...", password=..., secret: ...
    # The key may be quoted and the value may be quoted.
    $protected = [regex]::Replace(
        $protected,
        '(?i)((?:device[_-]?token|admin[_-]?token|ledger[_-]?token|pairing[_-]?token|access[_-]?token|refresh[_-]?token|client[_-]?secret|password|passwd|secret|token)\s*"?\s*[:=]\s*"?)([^"\s,;&}\]]+)',
        '$1***')
    # Device tokens and pairing secrets are long base64url strings. Keep URLs
    # and normal log text intact; only mask long unbroken token-like words.
    $protected = [regex]::Replace($protected, '\b[A-Za-z0-9_-]{40,}\b', '***')
    return $protected
}

function Write-OmLog {
    param(
        [ValidateSet('DEBUG', 'INFO', 'WARN', 'ERROR')][string]$Level = 'INFO',
        [Parameter(Mandatory = $true)][string]$Message,
        [AllowNull()]$Data = $null
    )
    $line = "{0} [{1}] {2}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Level, (Protect-OmText $Message)
    if ($null -ne $Data) {
        try {
            $json = $Data | ConvertTo-Json -Depth 8 -Compress
        } catch {
            $json = [string]$Data
        }
        $line += " " + (Protect-OmText $json)
    }
    Write-Verbose $line
    if ($script:OmLogPath) {
        try {
            Add-Content -Path $script:OmLogPath -Value $line -Encoding UTF8
        } catch {
            # Logging must never break a deployment step.
        }
    }
}

function Get-OmLogTail {
    param([int]$Lines = 200)
    if (-not $script:OmLogPath -or -not (Test-Path $script:OmLogPath)) { return @() }
    if ($Lines -lt 1) { return @() }
    if ($Lines -gt 1000) { $Lines = 1000 }
    $all = @(Get-Content -Path $script:OmLogPath -Tail $Lines -ErrorAction SilentlyContinue)
    return @($all | ForEach-Object { Protect-OmText $_ })
}
