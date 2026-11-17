; Open Margin / 清衡 Windows one-click installer.
;
; Build with Inno Setup 6.3 or newer (ISCC.exe). No code-signing certificate is
; used in v1; the release page must publish the SHA256 and SmartScreen guidance.
;
; The installer bundles the deterministic setup engine, backend build context,
; compose.yaml and the Windows deployment files. It never ships secrets or .env.
; Runtime secrets are created by the setup engine under {app}\secrets.

#ifndef AppVersion
  #define AppVersion "1.3.0"
#endif

#ifndef BuildOutputDir
  #define BuildOutputDir "Output"
#endif

#ifndef VersionInfoVersion
  #define VersionInfoVersion "1.3.0.0"
#endif

#define RepoRoot "..\..\..\.."

[Setup]
AppId={{9D2D6B5A-7C4E-4F9A-9B3D-2E6F8A1C0D77}
AppName=清衡 Open Margin
AppVersion={#AppVersion}
AppVerName=清衡 Open Margin {#AppVersion}
VersionInfoVersion={#VersionInfoVersion}
AppPublisher=Open Margin
AppPublisherURL=https://github.com/zxyzx0430/open-margin
AppSupportURL=https://github.com/zxyzx0430/open-margin/issues
AppUpdatesURL=https://github.com/zxyzx0430/open-margin/releases
DefaultDirName={code:GetDefaultInstallDir}
DefaultGroupName=清衡 Open Margin
DisableProgramGroupPage=yes
; Always show the directory page so users can install on D:/E: instead of C:.
DisableDirPage=no
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
OutputDir={#BuildOutputDir}
OutputBaseFilename=OpenMargin-Setup
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
UninstallDisplayName=清衡 Open Margin
UninstallDisplayIcon={sys}\shell32.dll
SetupLogging=yes
; Restart Manager lets Setup detect programs holding files that are replaced.
; PrepareToInstall (see [Code]) stops the Open Margin wizard/watchdog/compose
; before this check runs. AppMutex is intentionally not set here because the
; wizard does not own a named mutex; single-instance reuse is handled through
; setup-web.json and Stop-OpenMargin.ps1.
CloseApplications=yes
RestartApplications=no
UsePreviousAppDir=yes
DirExistsWarning=no
AllowNoIcons=yes

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; GroupDescription: "附加任务："; Flags: unchecked

[Files]
; Upgrade hook helpers. dontcopy keeps them out of the installed payload copy;
; PrepareToInstall extracts them to {tmp} and runs Stop-OpenMargin.ps1 before
; old files are replaced. Keep these near the top: solid compression extracts
; files in listing order, so a late dontcopy entry would decompress the whole
; payload first.
Source: "{#RepoRoot}\deploy\windows\setup\installer\Lifecycle.Common.ps1"; Flags: dontcopy noencryption
Source: "{#RepoRoot}\deploy\windows\setup\installer\Stop-OpenMargin.ps1"; Flags: dontcopy noencryption
; Setup engine, web wizard and runtime helpers. Build-only files are excluded.
Source: "{#RepoRoot}\deploy\windows\setup\*"; DestDir: "{app}\deploy\windows\setup"; Flags: ignoreversion recursesubdirs createallsubdirs; Excludes: "installer\OpenMargin.iss,installer\Build-Installer.ps1,installer\Setup.cmd,installer\Output\*,installer\build\*,installer\.gitignore,tests\*"
; The rest of deploy/windows (legacy CLI installers and gateway configs).
Source: "{#RepoRoot}\deploy\windows\*"; DestDir: "{app}\deploy\windows"; Flags: ignoreversion recursesubdirs createallsubdirs; Excludes: "setup\*"
; Wallos overlay and backup helper required by compose.yaml.
Source: "{#RepoRoot}\deploy\wallos-extension\*"; DestDir: "{app}\deploy\wallos-extension"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#RepoRoot}\deploy\backup.py"; DestDir: "{app}\deploy"; Flags: ignoreversion
; Backend build context.
Source: "{#RepoRoot}\backend\*"; DestDir: "{app}\backend"; Flags: ignoreversion recursesubdirs createallsubdirs; Excludes: "__pycache__\*,*.pyc,.pytest_cache\*"
Source: "{#RepoRoot}\compose.yaml"; DestDir: "{app}"; Flags: ignoreversion
; Minimal runtime/legal documentation.
Source: "{#RepoRoot}\README.md"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#RepoRoot}\LICENSE"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#RepoRoot}\THIRD_PARTY.md"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#RepoRoot}\SECURITY.md"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#RepoRoot}\docs\DEPLOY_WINDOWS.md"; DestDir: "{app}\docs"; Flags: ignoreversion
Source: "{#RepoRoot}\docs\WINDOWS_PRIVATE_FIRST_CONNECTION.md"; DestDir: "{app}\docs"; Flags: ignoreversion
Source: "{#RepoRoot}\docs\V1_3_0_WINDOWS_SETUP_RELEASE_NOTES.md"; DestDir: "{app}\docs"; Flags: ignoreversion

[Dirs]
; The engine creates secrets/ at runtime; never remove it on uninstall.
Name: "{app}\secrets"; Flags: uninsneveruninstall

[Icons]
Name: "{group}\清衡设置"; Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""{app}\deploy\windows\setup\installer\Launch-Setup.ps1"" -AppRoot ""{app}"""; WorkingDir: "{app}"; Comment: "打开清衡设置向导"; IconFilename: "{sys}\shell32.dll"; IconIndex: 21
Name: "{group}\清衡账本"; Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""{app}\deploy\windows\setup\installer\Launch-Ledger.ps1"" -AppRoot ""{app}"""; WorkingDir: "{app}"; Comment: "打开本机账本 http://127.0.0.1:18082/"; IconFilename: "{sys}\shell32.dll"; IconIndex: 13
Name: "{group}\安装说明"; Filename: "{app}\deploy\windows\setup\installer\README-INSTALL.txt"; Comment: "SmartScreen 与 SHA256 校验说明"
Name: "{autodesktop}\清衡设置"; Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""{app}\deploy\windows\setup\installer\Launch-Setup.ps1"" -AppRoot ""{app}"""; WorkingDir: "{app}"; Comment: "打开清衡设置向导"; IconFilename: "{sys}\shell32.dll"; IconIndex: 21; Tasks: desktopicon

[Run]
; Apply deterministic ACLs: app root writable for .env, code read-only,
; secrets restricted to the installing user/Admins/SYSTEM.
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\deploy\windows\setup\installer\Set-AppAcl.ps1"" -AppRoot ""{app}"" -OriginalUser ""{username}"""; StatusMsg: "正在配置安装目录权限..."; Flags: runhidden waituntilterminated
; Normalize installed scripts to UTF-8 with BOM so Windows PowerShell 5.1
; reads the Chinese literals correctly. This is idempotent.
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\deploy\windows\setup\installer\Fix-ScriptEncoding.ps1"" -Root ""{app}"""; StatusMsg: "正在准备本机脚本..."; Flags: runhidden waituntilterminated
; A one-time URL ACL lets the un-elevated wizard bind 127.0.0.1 setup ports.
; The reservation is narrowed to the installing user + Administrators.
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\deploy\windows\setup\installer\Install-UrlAcl.ps1"" -OriginalUser ""{username}"""; StatusMsg: "正在配置本机向导端口..."; Flags: runhidden waituntilterminated
; Start the wizard elevated so Docker/Tailscale/URL ACL setup needs only the
; installer's one UAC. Launch-Setup opens the browser through explorer.exe so
; the browser itself stays at the original user's integrity level.
; /NOELEVATE selects the original user-level wizard instead; /NOAUTOSTART
; disables both postinstall entries for automation. skipifsilent is deliberately
; not set: a silent install still opens the wizard unless /NOAUTOSTART is passed.
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""{app}\deploy\windows\setup\installer\Launch-Setup.ps1"" -AppRoot ""{app}"""; Description: "立即打开清衡设置向导"; Flags: postinstall nowait runhidden; Check: ShouldRunElevatedWizard
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""{app}\deploy\windows\setup\installer\Launch-Setup.ps1"" -AppRoot ""{app}"" -NoElevate"; Description: "以当前用户权限打开清衡设置向导"; Flags: postinstall nowait runhidden runasoriginaluser; Check: ShouldRunUnelevatedWizard

[UninstallRun]
; Inno Setup executes [UninstallRun] entries as the first uninstallation step,
; while installed files (including this script) still exist. The script stops
; the hidden wizard, watchdog and compose project, removes only our Tailscale
; route and URL ACLs, and preserves secrets/, .env, Docker volumes and user
; state. waituntilterminated is required so Inno does not delete files while
; cleanup is still running. RunOnceId prevents duplicate execution when several
; installer versions appended to the uninstall log.
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\deploy\windows\setup\installer\Uninstall-OpenMargin.ps1"" -AppRoot ""{app}"" -StateRoot ""{localappdata}\OpenMargin"""; Flags: runhidden waituntilterminated skipifdoesntexist; RunOnceId: "OpenMarginLifecycleUninstall"

[Code]
var
  PreviousInstallStopped: Boolean;

function HasCmdLineParam(const Value: String): Boolean;
var
  I: Integer;
begin
  Result := False;
  for I := 1 to ParamCount do
  begin
    if Lowercase(ParamStr(I)) = Lowercase(Value) then
    begin
      Result := True;
      Exit;
    end;
  end;
end;

function NoAutoStart(): Boolean;
begin
  { Automation can pass /NOAUTOSTART to keep the postinstall wizard closed. }
  Result := not HasCmdLineParam('/NOAUTOSTART');
end;

function ShouldRunElevatedWizard(): Boolean;
begin
  Result := NoAutoStart() and (not HasCmdLineParam('/NOELEVATE'));
end;

function ShouldRunUnelevatedWizard(): Boolean;
begin
  Result := NoAutoStart() and HasCmdLineParam('/NOELEVATE');
end;

function GetDefaultInstallDir(Param: String): String;
begin
  { Prefer D:, then E:, then the system drive. Users can still change the
    directory on the wizard's directory page. }
  if DirExists('D:\') then
    Result := 'D:\OpenMargin\app'
  else if DirExists('E:\') then
    Result := 'E:\OpenMargin\app'
  else
    Result := ExpandConstant('{sd}\OpenMargin\app');
end;

procedure LogLifecycle(const Message: string);
begin
  Log('OpenMargin lifecycle: ' + Message);
end;

procedure StopPreviousInstall();
var
  PowerShell: String;
  StopScript: String;
  AppDir: String;
  StateDir: String;
  Parameters: String;
  ComposeFile: String;
  ResultCode: Integer;
begin
  if PreviousInstallStopped then
    Exit;
  PreviousInstallStopped := True;

  AppDir := ExpandConstant('{app}');
  StateDir := ExpandConstant('{localappdata}\OpenMargin');
  PowerShell := ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe');

  { The helper is shipped with Flags: dontcopy so this works on the very first
    upgrade from a build that has no lifecycle script on disk yet. }
  StopScript := ExpandConstant('{tmp}\Stop-OpenMargin.ps1');
  try
    ExtractTemporaryFile('Lifecycle.Common.ps1');
    ExtractTemporaryFile('Stop-OpenMargin.ps1');
  except
    LogLifecycle('temporary stop helper could not be extracted');
  end;

  if FileExists(StopScript) then
  begin
    Parameters := '-NoProfile -ExecutionPolicy Bypass -File "' + StopScript +
      '" -AppRoot "' + AppDir + '" -StateRoot "' + StateDir + '" -ForInstall';
    if Exec(PowerShell, Parameters, AppDir, SW_HIDE, ewWaitUntilTerminated, ResultCode) then
      LogLifecycle('stop helper exit code ' + IntToStr(ResultCode))
    else
      LogLifecycle('stop helper could not be started');
  end
  else
  begin
    { Fallback for upgrades from builds that predate the lifecycle helper. }
    if FileExists(AppDir + '\deploy\windows\setup\open-margin-setup.ps1') then
    begin
      Parameters := '-NoProfile -ExecutionPolicy Bypass -File "' + AppDir +
        '\deploy\windows\setup\open-margin-setup.ps1" uninstall-watchdog --state-root "' +
        StateDir + '"';
      Exec(PowerShell, Parameters, AppDir, SW_HIDE, ewWaitUntilTerminated, ResultCode);
    end
    else
    begin
      Exec(ExpandConstant('{sys}\schtasks.exe'), '/End /TN OpenMarginWatchdog',
        AppDir, SW_HIDE, ewWaitUntilTerminated, ResultCode);
    end;

    ComposeFile := AppDir + '\compose.yaml';
    if FileExists(ComposeFile) then
      Exec('docker.exe', 'compose --project-name personal-finance-system --project-directory "' +
        AppDir + '" -f "' + ComposeFile + '" down',
        AppDir, SW_HIDE, ewWaitUntilTerminated, ResultCode);
  end;
end;

{ Called before Setup checks files that are in use (CloseApplications=yes) and
  before files are replaced. Stopping the old wizard/watchdog/compose here
  prevents stale code and file locks during upgrade/repair. }
function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  StopPreviousInstall();
  Result := '';
end;

function InitializeSetup(): Boolean;
begin
  Result := True;
end;
