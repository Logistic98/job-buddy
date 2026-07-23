from __future__ import annotations

import os
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
        self.processes: list[subprocess.Popen[str]] = []

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        for process in self.processes:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=2)
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
            f"start_service test-service {shlex.quote(str(dummy))} "
            f"{shlex.quote(self.health_url)} {self.server.server_address[1]}"
        )

        self.assertNotEqual(0, result.returncode)
        self.assertIn("is held by external pid=", result.stderr)
        self.assertFalse(marker.exists())

    def test_wait_for_http_rejects_dead_managed_process_before_stale_health(self):
        (self.pid_dir / "test-service.pid").write_text("99999999\n", encoding="utf-8")
        (self.log_dir / "test-service.log").write_text(
            "startup failed\n", encoding="utf-8"
        )

        result = self.run_bash(
            f"START_ALL_READY_TIMEOUT_SECONDS=1 wait_for_http test-service "
            f"{shlex.quote(self.health_url)} {self.server.server_address[1]}"
        )

        self.assertNotEqual(0, result.returncode)
        self.assertIn("exited before readiness", result.stderr)
        self.assertNotIn("ready:", result.stdout)

    def test_start_service_replaces_repository_listener_started_after_stop(self):
        marker = self.root / "started"
        dummy = self.root / "dummy.sh"
        dummy.write_text(
            "\n".join(
                [
                    "#!/usr/bin/env bash",
                    f"touch {shlex.quote(str(marker))}",
                    "",
                ]
            ),
            encoding="utf-8",
        )
        dummy.chmod(0o755)
        old_process = subprocess.Popen(
            ["bash", "-c", "while true; do sleep 1; done"],
            cwd=REPO_ROOT / "agent-frontend",
            env=TEST_ENV,
            text=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        self.processes.append(old_process)

        result = self.run_bash(
            "\n".join(
                [
                    f"old_pid={old_process.pid}",
                    "listener_pids_for_port() {",
                    '  if process_is_running "$old_pid"; then echo "$old_pid"; fi',
                    "}",
                    f"start_service agent-frontend {shlex.quote(str(dummy))} '' 5173",
                    f"for attempt in {{1..20}}; do [[ -f {shlex.quote(str(marker))} ]] && break; sleep 0.05; done",
                ]
            )
        )
        old_process.wait(timeout=2)

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(marker.exists())
        self.assertIn("stopping unrecorded repository process", result.stdout)

    def test_wait_for_http_rejects_listener_outside_managed_tree(self):
        managed_process = subprocess.Popen(
            ["bash", "-c", "while true; do sleep 1; done"],
            cwd=self.root,
            env=TEST_ENV,
            text=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        self.processes.append(managed_process)
        (self.pid_dir / "test-service.pid").write_text(
            f"{managed_process.pid}\n",
            encoding="utf-8",
        )

        result = self.run_bash(
            "\n".join(
                [
                    f"listener_pids_for_port() {{ echo {os.getpid()}; }}",
                    f"wait_for_http test-service {shlex.quote(self.health_url)} "
                    f"{self.server.server_address[1]}",
                ]
            )
        )

        self.assertNotEqual(0, result.returncode)
        self.assertIn("listener outside managed", result.stderr)
        self.assertNotIn("ready:", result.stdout)

    def test_main_rolls_back_when_startup_fails(self):
        rollback_marker = self.root / "rolled-back"

        result = self.run_bash(
            "\n".join(
                [
                    "start_all_services() { STARTED_SERVICES=(agent-sandbox); return 1; }",
                    f"rollback_started_services() {{ touch {shlex.quote(str(rollback_marker))}; }}",
                    "main",
                ]
            )
        )

        self.assertNotEqual(0, result.returncode)
        self.assertTrue(rollback_marker.exists())


if __name__ == "__main__":
    unittest.main()
