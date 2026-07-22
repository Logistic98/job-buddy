from __future__ import annotations

import shlex
import subprocess
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
START_ALL = REPO_ROOT / "scripts" / "start-all.sh"
TEST_ENV = {
    "PATH": "/usr/bin:/bin:/usr/sbin:/sbin",
    "START_ALL_CLEANUP_ENABLED": "0",
}


class _HealthyHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"ok")

    def log_message(self, _format, *_args):
        return


class StartAllSafetyTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.pid_dir = self.root / "pids"
        self.log_dir = self.root / "logs"
        self.pid_dir.mkdir()
        self.log_dir.mkdir()
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), _HealthyHandler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.health_url = f"http://127.0.0.1:{self.server.server_address[1]}/health"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.temp_dir.cleanup()

    def run_bash(self, statement: str) -> subprocess.CompletedProcess[str]:
        command = "\n".join(
            [
                f"source {shlex.quote(str(START_ALL))}",
                f"PID_DIR={shlex.quote(str(self.pid_dir))}",
                f"LOG_DIR={shlex.quote(str(self.log_dir))}",
                statement,
            ]
        )
        return subprocess.run(
            ["bash", "-c", command],
            cwd=REPO_ROOT,
            env=TEST_ENV,
            text=True,
            capture_output=True,
            check=False,
        )

    def test_start_service_rejects_unmanaged_healthy_process(self):
        marker = self.root / "started"
        dummy = self.root / "dummy.sh"
        dummy.write_text(
            f"#!/usr/bin/env bash\ntouch {shlex.quote(str(marker))}\n",
            encoding="utf-8",
        )
        dummy.chmod(0o755)

        result = self.run_bash(
            f"start_service test-service {shlex.quote(str(dummy))} '' "
            f"{shlex.quote(self.health_url)}"
        )

        self.assertNotEqual(0, result.returncode)
        self.assertIn("unmanaged process is already serving", result.stderr)
        self.assertFalse(marker.exists())

    def test_wait_for_http_rejects_dead_managed_process_before_stale_health(self):
        (self.pid_dir / "test-service.pid").write_text("99999999\n", encoding="utf-8")
        (self.log_dir / "test-service.log").write_text("startup failed\n", encoding="utf-8")

        result = self.run_bash(
            f"START_ALL_READY_TIMEOUT_SECONDS=1 wait_for_http test-service "
            f"{shlex.quote(self.health_url)}"
        )

        self.assertNotEqual(0, result.returncode)
        self.assertIn("exited before readiness", result.stderr)
        self.assertNotIn("ready:", result.stdout)


if __name__ == "__main__":
    unittest.main()
