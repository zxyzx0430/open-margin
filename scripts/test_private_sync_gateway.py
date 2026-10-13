"""Actual nginx negative-route tests with a synthetic backend; no production access."""
import http.server
import pathlib
import shutil
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
CONF = ROOT / "deploy/windows/private-sync-gateway.conf"

def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]

class FakeFinance(http.server.BaseHTTPRequestHandler):
    calls = []
    def log_message(self, *args):
        pass
    def handle_request(self):
        self.calls.append((self.command, self.path, self.headers.get("X-Device-Token")))
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"server_id":"0123456789abcdef","head_revision":1,"items":[],"cursor":0,"has_more":false}')
    do_GET = handle_request
    do_POST = handle_request
    do_PUT = handle_request

def main():
    if not shutil.which("nginx"):
        raise SystemExit("nginx not installed; test requires nginx")
    upstream = free_port()
    gateway = free_port()
    server = http.server.ThreadingHTTPServer(("127.0.0.1", upstream), FakeFinance)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        with tempfile.TemporaryDirectory(prefix="om-private-sync-nginx-") as work:
            base = pathlib.Path(work)
            raw = CONF.read_text().replace("finance-api:8767", f"127.0.0.1:{upstream}")
            raw = raw.replace("listen 80 default_server;", f"listen 127.0.0.1:{gateway} default_server;")
            (base / "gateway.conf").write_text(raw)
            (base / "nginx.conf").write_text(
                f"pid {base}/nginx.pid; error_log stderr; events {{ }} "
                f"http {{ access_log off; include {base}/gateway.conf; }}"
            )
            conf = str(base / "nginx.conf")
            subprocess.run(["nginx", "-t", "-c", conf], check=True, capture_output=True)
            process = subprocess.Popen(["nginx", "-c", conf, "-g", "daemon off;"],
                                       stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
            try:
                time.sleep(0.3)
                if process.poll() is not None:
                    raise AssertionError("nginx exited unexpectedly: " + process.stderr.read().decode())
                def request(path, method="GET"):
                    req = urllib.request.Request(f"http://127.0.0.1:{gateway}{path}",
                        headers={"X-Device-Token": "synthetic-test-only"}, method=method)
                    try:
                        with urllib.request.urlopen(req, timeout=4) as resp:
                            return resp.status
                    except urllib.error.HTTPError as err:
                        return err.code
                allowed = [
                    ("/finance/health", "GET"),
                    ("/finance/api/v2/sync/changes?since=0&limit=1", "GET"),
                    ("/finance/api/v2/sync/push", "POST"),
                    ("/finance/api/v1/transactions/batch", "POST"),
                    ("/finance/api/v1/transactions/correct-rounding", "POST"),
                ]
                for path, method in allowed:
                    assert request(path, method) == 200, (path, method)
                count = len(FakeFinance.calls)
                denied = [
                    ("/", "GET"), ("/finance/wallos/", "GET"),
                    ("/finance/wallos/index.php", "GET"),
                    ("/finance/mcp", "GET"), ("/finance/oauth/authorize", "GET"),
                    ("/finance/api/v1/transactions", "GET"),
                    ("/finance/api/v2/sync/changes", "POST"),
                    ("/finance/api/v2/sync/push", "GET"),
                    ("/finance/api/v2/sync/changes", "PUT"),
                    ("/finance/api/v2/sync/changes/extra", "GET"),
                    ("/finance/api%2Fv2%2Fsync%2Fpush", "POST"),
                    ("/finance/wallos/%2e%2e/oauth/authorize", "GET"),
                ]
                for path, method in denied:
                    code = request(path, method)
                    assert code in (404, 405), (path, method, code)
                assert len(FakeFinance.calls) == count, "Denied request reached backend"
                assert len(FakeFinance.calls) == len(allowed)
                assert all(token == "synthetic-test-only" for _, _, token in FakeFinance.calls[1:])
                print(f"PASS: {len(allowed)} allowed requests, {len(denied)} denied, no bypass.")
            finally:
                process.terminate()
                try:
                    process.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
    finally:
        server.shutdown()
        server.server_close()

if __name__ == "__main__":
    main()
