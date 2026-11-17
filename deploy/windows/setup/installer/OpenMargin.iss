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
#define AppIdValue "{9D2D6B5A-7C4E-4F9A-9B3D-2E6F8A1C0D77}"

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
; Docker Desktop requires Windows 10 21H2 (build 19044) or newer on 64-bit.
; Windows 10 22H2 (19045) is recommended; the [Code] section warns about 21H2.
MinVersion=10.0.19044
LanguageDetectionMethod=uilanguage
; Shown before the Ready page: hardware, Docker/Tailscale and migration notes.
InfoBeforeFile={#SourcePath}\REQUIREMENTS.txt
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
; Chinese is the default and the product UI language. The bundled file keeps
; local and CI builds deterministic even if the compiler language pack is not
; installed. English remains available for non-Chinese Windows UI languages.
Name: "chinesesimplified"; MessagesFile: "{#SourcePath}\languages\ChineseSimplified.isl"
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

; Extracted to {tmp} so the custom Docker notice page can show it before any
; files are installed. The same file is also installed with the setup files.
Source: "{#SourcePath}\DockerDesktopNotice.txt"; DestDir: "{tmp}"; Flags: dontcopy

[Dirs]
; The engine creates secrets/ at runtime; never remove it on uninstall.
Name: "{app}\secrets"; Flags: uninsneveruninstall

[Icons]
Name: "{group}\清衡设置"; Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""{app}\deploy\windows\setup\installer\Launch-Setup.ps1"" -AppRoot ""{app}"""; WorkingDir: "{app}"; Comment: "打开清衡设置向导"; IconFilename: "{sys}\shell32.dll"; IconIndex: 21
Name: "{group}\清衡账本"; Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""{app}\deploy\windows\setup\installer\Launch-Ledger.ps1"" -AppRoot ""{app}"""; WorkingDir: "{app}"; Comment: "打开本机账本 http://127.0.0.1:18082/"; IconFilename: "{sys}\shell32.dll"; IconIndex: 13
Name: "{group}\安装说明"; Filename: "{app}\deploy\windows\setup\installer\README-INSTALL.txt"; Comment: "SmartScreen、SHA256、系统要求与迁移说明"
Name: "{group}\Docker Desktop 许可说明"; Filename: "{app}\deploy\windows\setup\installer\DockerDesktopNotice.txt"; Comment: "Docker 订阅服务协议与免费/商业使用说明"
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
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File ""{app}\deploy\windows\setup\installer\Launch-Setup.ps1"" -AppRoot ""{app}"""; Description: "立即打开清衡设置向导"; Flags: postinstall nowait runhidden runasoriginaluser

[UninstallRun]
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\deploy\windows\setup\open-margin-setup.ps1"" uninstall-watchdog"; Flags: runhidden waituntilterminated
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\deploy\windows\setup\installer\Install-UrlAcl.ps1"" -Remove"; Flags: runhidden waituntilterminated

[Code]
var
  DockerNoticePage: TWizardPage;
  DockerNoticeMemo: TNewMemo;
  DockerNoticeCheck: TNewCheckBox;
  DockerNoticeAccepted: Boolean;

function NormalizeDir(const Dir: String): String;
begin
  Result := Dir;
  while (Length(Result) > 1) and (Result[Length(Result)] = '\') do
    Result := Copy(Result, 1, Length(Result) - 1);
end;

function GetPreviousInstallDir(): String;
var
  Key: String;
  Value: String;
begin
  Result := '';
  Key := 'Software\Microsoft\Windows\CurrentVersion\Uninstall\{#AppIdValue}_is1';
  if RegQueryStringValue(HKLM, Key, 'InstallLocation', Value) and (Value <> '') then
    Result := Value
  else if RegQueryStringValue(HKCU, Key, 'InstallLocation', Value) and (Value <> '') then
    Result := Value;
  if Result = '' then
  begin
    if RegQueryStringValue(HKLM, Key, 'Inno Setup: App Path', Value) and (Value <> '') then
      Result := Value
    else if RegQueryStringValue(HKCU, Key, 'Inno Setup: App Path', Value) and (Value <> '') then
      Result := Value;
  end;
end;

procedure InitializeWizard();
var
  NoticeLines: TArrayOfString;
  NoticeText: String;
  I: Integer;
begin
  DockerNoticePage := CreateCustomPage(
    wpWelcome,
    'Docker Desktop 许可与依赖说明',
    '请阅读以下说明。稍后的“一键安装依赖”可能会安装 Docker Desktop。');

  DockerNoticeMemo := TNewMemo.Create(DockerNoticePage);
  DockerNoticeMemo.Parent := DockerNoticePage.Surface;
  DockerNoticeMemo.SetBounds(0, 0, DockerNoticePage.SurfaceWidth,
    DockerNoticePage.SurfaceHeight - ScaleY(56));
  DockerNoticeMemo.ReadOnly := True;
  DockerNoticeMemo.ScrollBars := ssVertical;

  try
    ExtractTemporaryFile('DockerDesktopNotice.txt');
    if LoadStringsFromFile(ExpandConstant('{tmp}\DockerDesktopNotice.txt'), NoticeLines) then
    begin
      NoticeText := '';
      for I := 0 to GetArrayLength(NoticeLines) - 1 do
        NoticeText := NoticeText + NoticeLines[I] + #13#10;
      DockerNoticeMemo.Text := NoticeText;
    end
    else
      DockerNoticeMemo.Text := '无法加载 Docker Desktop 许可说明。请阅读安装目录中的 DockerDesktopNotice.txt。';
  except
    DockerNoticeMemo.Text := '无法加载 Docker Desktop 许可说明。请阅读安装目录中的 DockerDesktopNotice.txt。';
  end;

  DockerNoticeCheck := TNewCheckBox.Create(DockerNoticePage);
  DockerNoticeCheck.Parent := DockerNoticePage.Surface;
  DockerNoticeCheck.SetBounds(0,
    DockerNoticeMemo.Top + DockerNoticeMemo.Height + ScaleY(8),
    DockerNoticePage.SurfaceWidth, ScaleY(40));
  DockerNoticeCheck.Caption := '我已阅读并理解上述说明，并将在向导安装 Docker Desktop 前确认我的使用类型。';
  DockerNoticeCheck.Checked := False;
  DockerNoticeAccepted := False;
end;

function InitializeSetup(): Boolean;
var
  Version: TWindowsVersion;
  FreeBytes: Int64;
  TotalBytes: Int64;
  MessageText: String;
begin
  Result := True;
  GetWindowsVersionEx(Version);

  if not IsWin64 then
  begin
    MsgBox('清衡只支持 64 位 Windows。安装已停止，请换用 64 位系统。', mbError, MB_OK);
    Result := False;
    Exit;
  end;

  if (Version.Major < 10) or ((Version.Major = 10) and (Version.Build < 19044)) then
  begin
    MsgBox('清衡需要 64 位 Windows 10 21H2（内部版本 19044）或更高版本。' + #13#10#13#10 +
      '请先运行 Windows Update 升级系统，然后重新运行安装器。', mbError, MB_OK);
    Result := False;
    Exit;
  end;

  if (Version.Major = 10) and (Version.Build < 19045) then
  begin
    MessageText := '检测到 Windows 10 21H2（内部版本 ' + IntToStr(Version.Build) + '）。' + #13#10#13#10 +
      'Windows 10 22H2 已停止支持；Docker Desktop 仍可运行，但建议升级到 Windows 11。' + #13#10 +
      '安装将继续，向导会再次检查系统环境。';
    MsgBox(MessageText, mbInformation, MB_OK);
  end;

  if GetSpaceOnDisk64(ExpandConstant('{sd}\'), FreeBytes, TotalBytes) and
     (FreeBytes < 15 * 1024 * 1024 * 1024) then
  begin
    MessageText := '系统盘可用空间不足 15 GB。Docker 镜像、容器和更新可能需要更多空间。' + #13#10#13#10 +
      '建议先清理磁盘，或确认已把 Docker 数据目录放到空间充足的磁盘。' + #13#10 +
      '安装将继续；空间不足时向导会失败并给出提示。';
    MsgBox(MessageText, mbInformation, MB_OK);
  end;
end;

function NextButtonClick(CurPageID: Integer): Boolean;
var
  ChosenDir: String;
  PreviousDir: String;
  DefaultDir: String;
  FreeBytes: Int64;
  TotalBytes: Int64;
  MessageText: String;
begin
  Result := True;

  if CurPageID = DockerNoticePage.ID then
  begin
    DockerNoticeAccepted := DockerNoticeCheck.Checked;
    if (not DockerNoticeAccepted) and (not WizardSilent) then
    begin
      MsgBox('请先勾选“我已阅读并理解上述说明”，再继续安装。', mbError, MB_OK);
      Result := False;
    end;
    Exit;
  end;

  if CurPageID = wpSelectDir then
  begin
    ChosenDir := NormalizeDir(WizardDirValue);
    PreviousDir := NormalizeDir(GetPreviousInstallDir());
    DefaultDir := NormalizeDir(ExpandConstant('{commonappdata}\OpenMargin\app'));

    if (PreviousDir <> '') and (CompareText(ChosenDir, PreviousDir) <> 0) and DirExists(PreviousDir) then
    begin
      MessageText := '检测到已有 Open Margin 安装：' + #13#10 + PreviousDir + #13#10#13#10 +
        '你选择安装到：' + #13#10 + ChosenDir + #13#10#13#10 +
        '继续会在磁盘上保留两套程序目录、secrets 和看门狗；旧 Docker 容器和命名卷不会自动迁移，也不会被删除。' + #13#10#13#10 +
        '建议先运行 Migrate-OpenMargin.ps1 备份数据并停止旧安装，再继续。' + #13#10#13#10 +
        '是否仍要继续安装到新目录？';
      if MsgBox(MessageText, mbConfirmation, MB_YESNO) = IDNO then
      begin
        Result := False;
        Exit;
      end;
    end
    else if (CompareText(ChosenDir, DefaultDir) <> 0) and DirExists(DefaultDir) then
    begin
      MessageText := '检测到默认目录已有 Open Margin 安装：' + #13#10 + DefaultDir + #13#10#13#10 +
        '你选择安装到：' + #13#10 + ChosenDir + #13#10#13#10 +
        '继续会保留旧的程序目录、secrets 和看门狗；旧 Docker 容器和命名卷不会自动迁移或删除。' + #13#10#13#10 +
        '建议先运行 Migrate-OpenMargin.ps1。是否仍要继续？';
      if MsgBox(MessageText, mbConfirmation, MB_YESNO) = IDNO then
      begin
        Result := False;
        Exit;
      end;
    end;

    if GetSpaceOnDisk64(ExtractFileDrive(ChosenDir) + '\', FreeBytes, TotalBytes) and
       (FreeBytes < 15 * 1024 * 1024 * 1024) then
    begin
      MessageText := '所选安装盘可用空间不足 15 GB。Docker 镜像和容器通常存放在系统盘；' + #13#10 +
        '如果系统盘空间不足，向导可能在部署阶段失败。是否仍要继续？';
      if MsgBox(MessageText, mbConfirmation, MB_YESNO) = IDNO then
        Result := False;
    end;
  end;
end;

procedure CurStepChanged(CurStep: TSetupStep);
var
  AckPath: String;
  AckText: String;
begin
  if (CurStep = ssPostInstall) and DockerNoticeAccepted then
  begin
    AckPath := ExpandConstant('{app}\docker-license-ack.txt');
    AckText := 'acknowledgedAt=' + GetDateTimeString('yyyy-mm-dd hh:nn:ss', '-', ':') + #13#10 +
      'installerVersion={#AppVersion}' + #13#10 +
      'notice=DockerDesktopNotice.txt' + #13#10;
    try
      SaveStringToFile(AckPath, AckText, False);
    except
      // The marker is advisory; never fail the installation because of it.
    end;
  end;
end;
