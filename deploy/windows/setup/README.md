# Windows setup engine and installer

This directory contains the deterministic Windows setup engine used by the
one-click installer and the local web wizard. It targets **Windows PowerShell
5.1** and does not require Git, Python, Node or PowerShell 7 to run the web
wizard.

## Entry points

| File | Purpose |
| --- | --- |
| `Start-OpenMarginSetupWeb.ps1` | Local wizard server on `127.0.0.1:18770-18779`. |
| `open-margin-setup.ps1` | CLI for advanced users/agents (`status`, `check`, `deploy`, `verify`, `disable`, `repair`, ...). |
| `Watchdog.ps1` | Hidden per-user watchdog copied to `%LOCALAPPDATA%\OpenMargin\watchdog` and started by a scheduled task. |
| `installer/Launch-Setup.ps1` | Start Menu launcher; picks the first free wizard port. |
| `installer/Install-UrlAcl.ps1` | One-time elevated `netsh http add urlacl` for loopback wizard ports. |

For a repo checkout (not the installed copy), run
`installer/Install-UrlAcl.ps1` once from an elevated PowerShell before
starting the wizard; `HttpListener` needs the loopback reservation.

## Build the installer (local/dev)

```powershell
# Double-click fallback, or:
powershell -NoProfile -ExecutionPolicy Bypass -File .\installer\Build-Installer.ps1

# Install Inno Setup 6.3+ first, or let the script offer winget:
winget install --id JRSoftware.InnoSetup --exact
```

`Build-Installer.ps1` locates `ISCC.exe` in Program Files, the registry, PATH or
the winget package cache. It writes `OpenMargin-Setup.exe` and
`OpenMargin-Setup.exe.sha256` under `installer/Output` by default. The
installer is unsigned by design for v1; publish the SHA256 and SmartScreen
guidance with every Release.

The Inno Setup script installs to `%ProgramData%\OpenMargin\app`, creates the
start-menu shortcuts “清衡设置” and “清衡账本”, reserves the loopback wizard
ports, and never ships `secrets/` or `.env`.

## Offline Docker bundle

`Build-OfflineBundle.ps1` builds the compose services, saves built images plus
base images to `images.tar`, compresses to
`OpenMargin-Offline-<version>.tar.gz`, and writes `.sha256` and
`.manifest.json`. It is a separate Release asset, never part of the installer.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\Build-OfflineBundle.ps1 -Version 1.3.0
```

Safety: it never runs `docker compose down`, never deletes volumes, and only
removes placeholder secret files it created itself.

## Tests

Pester 5 tests live under `tests/` when present (added by the engine thread).
CI runs them with `windows-setup-tests.yml`; if the folder is absent the
workflow exits successfully with a notice.

## Known issues in this scaffold (reported to the coordinator)

- `Invoke-OmElevatedDependencyInstall` and `Invoke-OmElevatedScript` call
  `pwsh.exe` (PowerShell 7). On a machine with only Windows PowerShell 5.1 the
  dependency-install UAC path fails. The engine must use
  `%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe` or detect both.
- The engine files are UTF-8 without BOM and contain Chinese literals, which
  Windows PowerShell 5.1 interprets using the ANSI code page. The installer
  normalizes installed copies with `installer/Fix-ScriptEncoding.ps1` (UTF-8
  with BOM), but the source tree itself still needs a proper BOM/ASCII fix.
- `WAITING_REBOOT` is written, but no `RunOnce`/auto-resume entry is registered;
  after a reboot the user must reopen “清衡设置”.
- `compose.yaml` in this baseline has no `windows-public-sync` service yet; the
  engine and workflows reference the profile. The runtime/compose thread must
  add the public-sync gateway and prebuilt-image support for the offline path.
