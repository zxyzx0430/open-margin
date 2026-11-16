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
DefaultDirName={commonappdata}\OpenMargin\app
DefaultGroupName=清衡 Open Margin
DisableProgramGroupPage=yes
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
UsePreviousAppDir=yes
DirExistsWarning=no
AllowNoIcons=yes

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; GroupDescription: "附加任务："; Flags: unchecked

[Files]
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
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\deploy\windows\setup\installer\Install-UrlAcl.ps1"""; StatusMsg: "正在配置本机向导端口..."; Flags: runhidden waituntilterminated
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""{app}\deploy\windows\setup\installer\Launch-Setup.ps1"" -AppRoot ""{app}"""; Description: "立即打开清衡设置向导"; Flags: postinstall nowait skipifsilent runhidden runasoriginaluser

[UninstallRun]
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\deploy\windows\setup\open-margin-setup.ps1"" uninstall-watchdog"; Flags: runhidden waituntilterminated
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\deploy\windows\setup\installer\Install-UrlAcl.ps1"" -Remove"; Flags: runhidden waituntilterminated

[Code]
function InitializeSetup(): Boolean;
begin
  Result := True;
end;
