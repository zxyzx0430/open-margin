# Local read-only MCP for stdio-capable desktop AI clients.
# No stdout log messages: stdout is reserved for JSON-RPC protocol frames.
$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Set-Location $root

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
  [Console]::Error.WriteLine("Open Margin local MCP: Docker Desktop is required.")
  exit 1
}

# No TTY: preserve the stdio wire protocol end to end.
& docker compose exec -T finance-api python /app/mcp_stdio.py
$code = $LASTEXITCODE
if ($code -ne 0) {
  [Console]::Error.WriteLine("Open Margin local MCP stopped (code $code). Check Docker Desktop.")
}
exit $code
