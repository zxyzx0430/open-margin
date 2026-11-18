# Windows Credential Manager access for the local Wallos administrator.
# The bridge process must run under the original installing user's logon.

$script:OmWallosCredentialTarget = 'OpenMargin/WallosLogin'

function Initialize-OmCredentialNative {
    if ('OpenMarginCredentialNative' -as [type]) { return }
    $source = @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;

public static class OpenMarginCredentialNative
{
    private const int CRED_TYPE_GENERIC = 1;
    private const int CRED_PERSIST_LOCAL_MACHINE = 2;

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct CREDENTIAL
    {
        public UInt32 Flags;
        public UInt32 Type;
        public string TargetName;
        public string Comment;
        public long LastWritten;
        public UInt32 CredentialBlobSize;
        public IntPtr CredentialBlob;
        public UInt32 Persist;
        public UInt32 AttributeCount;
        public IntPtr Attributes;
        public string TargetAlias;
        public string UserName;
    }

    [DllImport("Advapi32.dll", EntryPoint = "CredWriteW", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CredWrite(ref CREDENTIAL credential, UInt32 flags);

    [DllImport("Advapi32.dll", EntryPoint = "CredReadW", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CredRead(string target, int type, int flags, out IntPtr credential);

    [DllImport("Advapi32.dll", EntryPoint = "CredDeleteW", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CredDelete(string target, int type, int flags);

    [DllImport("Advapi32.dll", SetLastError = false)]
    private static extern void CredFree(IntPtr buffer);

    public static void Write(string target, string userName, string password)
    {
        byte[] bytes = Encoding.Unicode.GetBytes(password ?? String.Empty);
        if (bytes.Length > 512) throw new ArgumentOutOfRangeException("password", "Credential Manager limits the credential blob to 512 bytes.");
        IntPtr blob = IntPtr.Zero;
        try
        {
            if (bytes.Length > 0)
            {
                blob = Marshal.AllocHGlobal(bytes.Length);
                Marshal.Copy(bytes, 0, blob, bytes.Length);
            }
            CREDENTIAL credential = new CREDENTIAL();
            credential.Type = CRED_TYPE_GENERIC;
            credential.TargetName = target;
            credential.UserName = userName;
            credential.CredentialBlob = blob;
            credential.CredentialBlobSize = (UInt32)bytes.Length;
            credential.Persist = CRED_PERSIST_LOCAL_MACHINE;
            if (!CredWrite(ref credential, 0)) throw new Win32Exception(Marshal.GetLastWin32Error());
        }
        finally
        {
            if (blob != IntPtr.Zero)
            {
                for (int i = 0; i < bytes.Length; i++) Marshal.WriteByte(blob, i, 0);
                Marshal.FreeHGlobal(blob);
            }
            Array.Clear(bytes, 0, bytes.Length);
        }
    }

    public static string[] Read(string target)
    {
        IntPtr pointer;
        if (!CredRead(target, CRED_TYPE_GENERIC, 0, out pointer))
        {
            int error = Marshal.GetLastWin32Error();
            if (error == 1168) return null; // ERROR_NOT_FOUND
            throw new Win32Exception(error);
        }
        try
        {
            CREDENTIAL credential = (CREDENTIAL)Marshal.PtrToStructure(pointer, typeof(CREDENTIAL));
            string password = credential.CredentialBlobSize == 0 || credential.CredentialBlob == IntPtr.Zero
                ? String.Empty
                : Marshal.PtrToStringUni(credential.CredentialBlob, (int)credential.CredentialBlobSize / 2);
            return new string[] { credential.UserName ?? String.Empty, password };
        }
        finally { CredFree(pointer); }
    }

    public static void Delete(string target)
    {
        if (!CredDelete(target, CRED_TYPE_GENERIC, 0))
        {
            int error = Marshal.GetLastWin32Error();
            if (error != 1168) throw new Win32Exception(error); // ERROR_NOT_FOUND is idempotent.
        }
    }
}
'@
    Add-Type -TypeDefinition $source -Language CSharp -ErrorAction Stop
}

function Save-OmCredentialDirect {
    param(
        [Parameter(Mandatory = $true)][string]$Username,
        [Parameter(Mandatory = $true)][string]$Password
    )
    try {
        Initialize-OmCredentialNative
        [OpenMarginCredentialNative]::Write($script:OmWallosCredentialTarget, $Username, $Password)
        return [pscustomobject]@{ ok = $true; code = 'CREDENTIAL_SAVED' }
    } catch {
        return [pscustomobject]@{ ok = $false; code = 'CREDENTIAL_STORE_FAILED'; message = '无法将登录凭据保存到当前 Windows 用户的凭据管理器。' }
    }
}

function Get-OmCredentialDirect {
    try {
        Initialize-OmCredentialNative
        $entry = [OpenMarginCredentialNative]::Read($script:OmWallosCredentialTarget)
        if ($null -eq $entry) { return [pscustomobject]@{ ok = $false; code = 'CREDENTIAL_NOT_FOUND' } }
        return [pscustomobject]@{ ok = $true; code = 'CREDENTIAL_FOUND'; username = [string]$entry[0]; password = [string]$entry[1] }
    } catch {
        return [pscustomobject]@{ ok = $false; code = 'CREDENTIAL_READ_FAILED' }
    }
}

function Remove-OmCredentialDirect {
    try {
        Initialize-OmCredentialNative
        [OpenMarginCredentialNative]::Delete($script:OmWallosCredentialTarget)
        return [pscustomobject]@{ ok = $true; code = 'CREDENTIAL_REMOVED' }
    } catch {
        return [pscustomobject]@{ ok = $false; code = 'CREDENTIAL_REMOVE_FAILED' }
    }
}

function New-OmLedgerLoginPagePayload {
    param(
        [Parameter(Mandatory = $true)][string]$Username,
        [Parameter(Mandatory = $true)][string]$Password,
        [Parameter(Mandatory = $true)][ValidateRange(1, 65535)][int]$WallosPort,
        [Parameter(Mandatory = $true)][string]$Nonce
    )
    $baseUrl = 'http://127.0.0.1:' + $WallosPort
    $userValue = [System.Net.WebUtility]::HtmlEncode($Username)
    $passwordValue = [System.Net.WebUtility]::HtmlEncode($Password)
    $html = @"
<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>正在登录清衡账本</title>
<body><p>正在安全打开本机账本…</p><form id="ledger-login" method="post" action="$baseUrl/login.php">
<input type="hidden" name="username" value="$userValue"><input type="hidden" name="password" value="$passwordValue"><input type="hidden" name="remember" value="on">
<noscript><button type="submit">继续登录</button></noscript></form>
<script nonce="$Nonce">document.getElementById('ledger-login').submit();</script></body></html>
"@
    return [pscustomobject]@{
        html = $html
        contentSecurityPolicy = ("default-src 'none'; script-src 'nonce-" + $Nonce + "'; form-action " + $baseUrl + "; base-uri 'none'; frame-ancestors 'none'")
    }
}

function Invoke-OmCredentialBridgeRequest {
    param(
        [Parameter(Mandatory = $true)][string]$PipeName,
        [Parameter(Mandatory = $true)][pscustomobject]$Request,
        [int]$TimeoutMilliseconds = 5000
    )
    $pipe = $null
    $writer = $null
    $reader = $null
    try {
        if ($PipeName -notmatch '^[A-Za-z0-9_-]{16,80}$') {
            return [pscustomobject]@{ ok = $false; code = 'CREDENTIAL_PIPE_INVALID' }
        }
        $pipe = New-Object System.IO.Pipes.NamedPipeClientStream('.', $PipeName, [System.IO.Pipes.PipeDirection]::InOut)
        $pipe.Connect($TimeoutMilliseconds)
        $utf8 = New-Object System.Text.UTF8Encoding($false)
        $writer = New-Object System.IO.StreamWriter($pipe, $utf8, 1024, $true)
        $writer.AutoFlush = $true
        $reader = New-Object System.IO.StreamReader($pipe, $utf8, $false, 1024, $true)
        $writer.WriteLine(($Request | ConvertTo-Json -Depth 4 -Compress))
        $responseText = $reader.ReadLine()
        if ([string]::IsNullOrWhiteSpace($responseText) -or $responseText.Length -gt 65536) {
            return [pscustomobject]@{ ok = $false; code = 'CREDENTIAL_PIPE_BAD_RESPONSE' }
        }
        return ($responseText | ConvertFrom-Json)
    } catch {
        return [pscustomobject]@{ ok = $false; code = 'CREDENTIAL_BRIDGE_UNAVAILABLE' }
    } finally {
        if ($reader) { try { $reader.Dispose() } catch { } }
        if ($writer) { try { $writer.Dispose() } catch { } }
        if ($pipe) { try { $pipe.Dispose() } catch { } }
    }
}

function Invoke-OmCredentialManager {
    param(
        [Parameter(Mandatory = $true)][ValidateSet('set', 'get', 'delete', 'attach', 'stop')][string]$Command,
        [string]$Username = '',
        [string]$Password = '',
        [int]$ServerPid = 0,
        [string]$OriginalUserSid = '',
        [string]$PipeName = '',
        [int]$TimeoutMilliseconds = 5000
    )
    if (-not $OriginalUserSid) { $OriginalUserSid = [string]$env:OPENMARGIN_ORIGINAL_USER_SID }
    if (-not $OriginalUserSid) { $OriginalUserSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value }
    if ($PipeName) {
        return (Invoke-OmCredentialBridgeRequest -PipeName $PipeName -Request ([pscustomobject]@{
            command = $Command; username = $Username; password = $Password; serverPid = $ServerPid
        }) -TimeoutMilliseconds $TimeoutMilliseconds)
    }
    $currentSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    if ($currentSid -ne $OriginalUserSid -and $Command -in @('set', 'get', 'delete')) {
        return [pscustomobject]@{ ok = $false; code = 'ORIGINAL_USER_CREDENTIAL_CONTEXT_REQUIRED' }
    }
    switch ($Command) {
        'set' { return (Save-OmCredentialDirect -Username $Username -Password $Password) }
        'get' { return (Get-OmCredentialDirect) }
        'delete' { return (Remove-OmCredentialDirect) }
        default { return [pscustomobject]@{ ok = $false; code = 'CREDENTIAL_BRIDGE_UNAVAILABLE' } }
    }
}
