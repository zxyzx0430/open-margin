"""Actual nginx negative-route tests with a synthetic backend; no production access.

Tests both Windows gateway configs:
  deploy/windows/private-sync-gateway.conf
  deploy/windows/public-sync-gateway.conf

Host nginx is used when available; otherwise the test runs real nginx containers
via Docker (no host nginx required). The public config additionally verifies
rate limiting, token pass-through, and that access logs never contain the token.
"""
import argparse
import http.server
import os
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
CONFS = {
    "private": ROOT / "deploy/windows/private-sync-gateway.conf",
    "public": ROOT / "deploy/windows/public-sync-gateway.conf",
}
NGINX_IMAGE = os.environ.get("OM_TEST_NGINX_IMAGE", "nginx:1.28-alpine")
# Local images sometimes available in constrained environments; the test still
# runs a real nginx binary inside Docker.
NGINX_FALLBACK_IMAGES = ["ommcppr11acceptance-wallos:latest", "nginx:alpine"]
ACTIVE_IMAGE = None
TOKEN = "synthetic-secret-device-token"

ALLOWED = [
    ("/finance/health", "GET"),
    ("/finance/api/v2/sync/changes?since=0&limit=1", "GET"),
    ("/finance/api/v2/sync/push", "POST"),
    ("/finance/api/v1/transactions/batch", "POST"),
    ("/finance/api/v1/transactions/correct-rounding", "POST"),
]
DENIED = [
    ("/", "GET"),
    ("/finance/health/", "GET"),
    ("/finance/health", "POST"),
    ("/finance/wallos/", "GET"),
    ("/finance/wallos/index.php", "GET"),
    ("/finance/mcp", "GET"),
    ("/finance/mcp/", "GET"),
    ("/finance/oauth/authorize", "GET"),
    ("/finance/api/v1/transactions", "GET"),
    ("/finance/api/v2/sync/changes", "POST"),
    ("/finance/api/v2/sync/changes", "PUT"),
    ("/finance/api/v2/sync/push", "GET"),
    ("/finance/api/v2/sync/changes/extra", "GET"),
    ("/finance/api%2Fv2%2Fsync%2Fpush", "POST"),
    ("/finance/wallos/%2e%2e/oauth/authorize", "GET"),
]


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def temp_work_root():
    preferred = pathlib.Path("E:/DevCaches/Temp")
    if preferred.exists():
        return tempfile.mkdtemp(prefix="om-gateway-nginx-", dir=str(preferred))
    return tempfile.mkdtemp(prefix="om-gateway-nginx-")


def request(port, path, method="GET", token=None):
    headers = {}
    if token:
        headers["X-Device-Token"] = token
    req = urllib.request.Request(f"http://127.0.0.1:{port}{path}", headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            return resp.status, resp.read(), dict(resp.headers)
    except urllib.error.HTTPError as err:
        return err.code, err.read(), dict(err.headers)


def wait_for_port(port, timeout=15):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            request(port, "/finance/health")
            return
        except (OSError, urllib.error.URLError):
            time.sleep(0.2)
    raise AssertionError(f"nginx did not start on port {port}")


class FakeFinance(http.server.BaseHTTPRequestHandler):
    calls = []
    def log_message(self, *args):
        pass
    def handle_request(self):
        token = self.headers.get("X-Device-Token")
        self.calls.append((self.command, self.path, token))
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("X-Echo-Token", token or "")
        self.end_headers()
        self.wfile.write(b'{"server_id":"0123456789abcdef","head_revision":1,"items":[],"cursor":0,"has_more":false}')
    do_GET = handle_request
    do_POST = handle_request
    do_PUT = handle_request


def assert_contract(port, backend_marker, access_log=None):
    for path, method in ALLOWED:
        status, body, headers = request(port, path, method, TOKEN)
        assert status == 200, (path, method, status)
        assert backend_marker in body, (path, method, body[:120])
        assert headers.get("X-Echo-Token") == TOKEN, (path, method, headers.get("X-Echo-Token"))
    for path, method in DENIED:
        status, body, _ = request(port, path, method, TOKEN)
        assert status in (404, 405), (path, method, status)
        assert backend_marker not in body, (path, method, "denied request reached backend")
    if access_log:
        text = pathlib.Path(access_log).read_text(errors="replace")
        assert TOKEN not in text, "device token leaked into access log"
        assert "since=" not in text, "query string leaked into access log"
        assert "GET /finance/health" in text, "expected access log line missing"


def test_rate_limit(port):
    codes = [request(port, "/finance/health")[0] for _ in range(12)]
    assert any(code in (429, 503) for code in codes), codes
    assert all(code in (200, 429, 503) for code in codes), codes


def run_local(conf_path, name):
    if not shutil.which("nginx"):
        return False
    upstream = free_port()
    gateway = free_port()
    FakeFinance.calls = []
    server = http.server.ThreadingHTTPServer(("127.0.0.1", upstream), FakeFinance)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    process = None
    try:
        with tempfile.TemporaryDirectory(prefix="om-nginx-local-") as work:
            base = pathlib.Path(work)
            raw = conf_path.read_text()
            raw = raw.replace("finance-api:8767", f"127.0.0.1:{upstream}")
            raw = raw.replace("listen 80 default_server;", f"listen 127.0.0.1:{gateway} default_server;")
            raw = raw.replace("access_log /var/log/nginx/access.log", "access_log logs/access.log")
            (base / "gateway.conf").write_text(raw)
            (base / "logs").mkdir(parents=True, exist_ok=True)
            base_posix = base.as_posix()
            (base / "nginx.conf").write_text(
                f"pid {base_posix}/nginx.pid; error_log stderr; events {{ }} "
                f"http {{ include {base_posix}/gateway.conf; }}"
            )
            for sub in ("client_body_temp", "proxy_temp", "fastcgi_temp", "uwsgi_temp", "scgi_temp"):
                (base / "temp" / sub).mkdir(parents=True, exist_ok=True)
            conf = str(base / "nginx.conf")
            prefix = base_posix + "/"
            subprocess.run(["nginx", "-t", "-c", conf, "-p", prefix], check=True, capture_output=True)
            process = subprocess.Popen(
                ["nginx", "-c", conf, "-p", prefix, "-g", "daemon off;"],
                stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
            )
            try:
                time.sleep(0.3)
                if process.poll() is not None:
                    raise AssertionError("nginx exited unexpectedly: " + process.stderr.read().decode())
                wait_for_port(gateway)
                log_path = None
                if name == "public":
                    log_path = base / "logs/access.log"
                assert_contract(gateway, b'"server_id"', log_path)
                if name == "public":
                    limited = raw.replace("rate=10r/s", "rate=1r/s").replace("burst=20", "burst=1")
                    (base / "rate.conf").write_text(limited)
                    other = free_port()
                    (base / "rate-nginx.conf").write_text(
                        f"pid {base_posix}/rate.pid; error_log stderr; events {{ }} "
                        f"http {{ include {base_posix}/rate.conf; }}"
                    )
                    subprocess.run(["nginx", "-t", "-c", str(base / "rate-nginx.conf"), "-p", prefix], check=True, capture_output=True)
                    rate_process = subprocess.Popen(
                        ["nginx", "-c", str(base / "rate-nginx.conf"), "-p", prefix, "-g", "daemon off;"],
                        stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
                    )
                    try:
                        wait_for_port(other)
                        test_rate_limit(other)
                    finally:
                        rate_process.terminate()
                        rate_process.wait(timeout=5)
                print(f"PASS local {name}: {len(ALLOWED)} allowed, {len(DENIED)} denied, no bypass.")
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
    return True


def docker(*args, **kwargs):
    return subprocess.run(["docker", *args], check=True, capture_output=True, text=True, **kwargs)


def image_has_nginx(image):
    result = subprocess.run(
        ["docker", "run", "--rm", "--entrypoint", "sh", image, "-c", "command -v nginx"],
        capture_output=True, text=True,
    )
    return result.returncode == 0


def ensure_image():
    global ACTIVE_IMAGE
    if ACTIVE_IMAGE:
        return ACTIVE_IMAGE
    for image in [NGINX_IMAGE] + NGINX_FALLBACK_IMAGES:
        inspect = subprocess.run(["docker", "image", "inspect", image], capture_output=True, text=True)
        if inspect.returncode != 0:
            pull = subprocess.run(["docker", "pull", image], capture_output=True, text=True)
            if pull.returncode != 0:
                continue
        if image_has_nginx(image):
            ACTIVE_IMAGE = image
            return image
    raise AssertionError("no Docker image with nginx is available")


def docker_start_backend(network, work, backend_name, backend_port=8767):
    backend_conf = work / "backend-nginx.conf"
    backend_conf.write_text(
        "pid /tmp/backend.pid; error_log stderr; events { }\n"
        "http { access_log off; server { listen 8767; server_name _;\n"
        "  add_header X-Echo-Token $http_x_device_token always;\n"
        "  location / { return 200 'backend-ok'; }\n"
        "} }\n"
    )
    docker(
        "run", "-d", "--rm", "--name", backend_name,
        "--network", network, "--network-alias", "finance-api",
        "-v", f"{work.as_posix()}:/work:ro",
        "--entrypoint", "nginx", ACTIVE_IMAGE,
        "-c", "/work/backend-nginx.conf", "-g", "daemon off;",
    )


def docker_start_gateway(network, work, gateway_name, host_port, backend_name):
    main_conf = work / "main-nginx.conf"
    main_conf.write_text(
        "pid /tmp/nginx.pid; error_log stderr; events { }\n"
        "http {\n"
        "  client_body_temp_path /tmp/nginx-client;\n"
        "  proxy_temp_path /tmp/nginx-proxy;\n"
        "  fastcgi_temp_path /tmp/nginx-fastcgi;\n"
        "  uwsgi_temp_path /tmp/nginx-uwsgi;\n"
        "  scgi_temp_path /tmp/nginx-scgi;\n"
        "  include /work/gateway.conf;\n"
        "}\n"
    )
    docker(
        "run", "-d", "--rm", "--name", gateway_name,
        "--network", network,
        "-p", f"127.0.0.1:{host_port}:80",
        "-v", f"{(work / 'gateway.conf').as_posix()}:/work/gateway.conf:ro",
        "-v", f"{main_conf.as_posix()}:/etc/nginx/nginx.conf:ro",
        "-v", f"{work.as_posix()}:/work",
        "--entrypoint", "nginx", ACTIVE_IMAGE,
        "-g", "daemon off;",
    )
    deadline = time.time() + 15
    while time.time() < deadline:
        running = subprocess.run(
            ["docker", "inspect", "-f", "{{.State.Running}}", gateway_name],
            capture_output=True, text=True,
        )
        if running.stdout.strip() != "true":
            logs = subprocess.run(["docker", "logs", gateway_name], capture_output=True, text=True)
            raise AssertionError(f"gateway {gateway_name} exited: {logs.stdout} {logs.stderr}")
        try:
            request(host_port, "/finance/health")
            return
        except (OSError, urllib.error.URLError):
            time.sleep(0.2)
    raise AssertionError(f"gateway {gateway_name} did not answer")


def run_docker(conf_path, name):
    if not shutil.which("docker"):
        return False
    if subprocess.run(["docker", "info"], capture_output=True).returncode != 0:
        return False
    ensure_image()
    work_root = pathlib.Path(temp_work_root())
    network = f"om-gw-test-{os.getpid()}-{int(time.time())}"
    backend_name = f"om-gw-backend-{os.getpid()}-{name}"
    gateway_name = f"om-gw-gateway-{os.getpid()}-{name}"
    rate_name = f"om-gw-rate-{os.getpid()}-{name}"
    try:
        work = work_root / name
        work.mkdir(parents=True)
        raw = conf_path.read_text()
        if name == "public":
            raw = raw.replace("access_log /var/log/nginx/access.log", "access_log /work/access.log")
        (work / "gateway.conf").write_text(raw)
        docker("network", "create", network)
        docker_start_backend(network, work, backend_name)
        gateway = free_port()
        docker_start_gateway(network, work, gateway_name, gateway, backend_name)
        log_path = work / "access.log" if name == "public" else None
        assert_contract(gateway, b"backend-ok", log_path)
        if name == "public":
            rate_dir = work_root / f"{name}-rate"
            rate_dir.mkdir()
            limited = raw.replace("rate=10r/s", "rate=1r/s").replace("burst=20", "burst=1")
            (rate_dir / "gateway.conf").write_text(limited)
            rate_port = free_port()
            docker_start_gateway(network, rate_dir, rate_name, rate_port, backend_name)
            test_rate_limit(rate_port)
        print(f"PASS docker {name}: {len(ALLOWED)} allowed, {len(DENIED)} denied, no bypass.")
        return True
    finally:
        for container in (rate_name, gateway_name, backend_name):
            subprocess.run(["docker", "rm", "-f", container], capture_output=True)
        subprocess.run(["docker", "network", "rm", network], capture_output=True)
        shutil.rmtree(work_root, ignore_errors=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--backend", choices=["auto", "docker", "local"], default="auto")
    parser.add_argument("--conf", choices=["both", "private", "public"], default="both")
    args = parser.parse_args()
    names = ["private", "public"] if args.conf == "both" else [args.conf]
    ran = []
    for name in names:
        conf = CONFS[name]
        if not conf.exists():
            raise SystemExit(f"missing config: {conf}")
        done = False
        if args.backend in ("auto", "docker"):
            done = run_docker(conf, name)
        if not done and args.backend in ("auto", "local"):
            done = run_local(conf, name)
        if not done:
            raise SystemExit("no nginx backend available (install nginx or Docker)")
        ran.append(name)
    print(f"PASS: tested {', '.join(ran)} gateway config(s).")


if __name__ == "__main__":
    main()
