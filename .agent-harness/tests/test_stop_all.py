from __future__ import annotations

import shlex
import subprocess
import tempfile
import time
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
STOP_ALL = REPO_ROOT / "scripts" / "stop-all.sh"
TEST_ENV = {
    "PATH": "/usr/bin:/bin:/usr/sbin:/sbin",
    "STOP_ALL_TIMEOUT_SECONDS": "1",
}


class StopAllSafetyTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.pid_dir = self.root / ".run" / "pids"
        self.backend_dir = self.root / "agent-backend"
        self.pid_dir.mkdir(parents=True)
        self.backend_dir.mkdir()
        self.processes: list[subprocess.Popen[str]] = []

    def tearDown(self):
        for process in self.processes:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=2)
        self.temp_dir.cleanup()

    def start_process(self, directory: Path) -> subprocess.Popen[str]:
        script = directory / "run-forever.sh"
        script.write_text(
            "#!/usr/bin/env bash\nwhile true; do sleep 1; done\n",
            encoding="utf-8",
        )
        script.chmod(0o755)
        process = subprocess.Popen(
            [str(script)],
            cwd=directory,
            env={"PATH": TEST_ENV["PATH"]},
            text=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        self.processes.append(process)
        time.sleep(0.1)
        return process

    def run_stop_all(self, listener_pid: int) -> subprocess.CompletedProcess[str]:
        command = "\n".join(
            [
                f"source {shlex.quote(str(STOP_ALL))}",
                f"ROOT_DIR={shlex.quote(str(self.root))}",
                f"PID_DIR={shlex.quote(str(self.pid_dir))}",
                "listener_pids_for_port() {",
                '  if [[ "$1" == "8080" ]]; then',
                f"    echo {listener_pid}",
                "  fi",
                "}",
                "main",
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

    def test_stops_unrecorded_process_owned_by_repository_module(self):
        process = self.start_process(self.backend_dir)

        result = self.run_stop_all(process.pid)
        process.wait(timeout=2)

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("stopping unrecorded repository process", result.stdout)
        self.assertIn("All repository services stopped.", result.stdout)

    def test_stops_recorded_wrapper_with_repository_child(self):
        service_script = self.backend_dir / "run-forever.sh"
        service_script.write_text(
            "#!/usr/bin/env bash\nwhile true; do sleep 1; done\n",
            encoding="utf-8",
        )
        service_script.chmod(0o755)
        child_pid_file = self.root / "child.pid"
        wrapper_script = self.root / "wrapper.sh"
        wrapper_script.write_text(
            "\n".join(
                [
                    "#!/usr/bin/env bash",
                    f"{shlex.quote(str(service_script))} &",
                    f"echo $! > {shlex.quote(str(child_pid_file))}",
                    "wait",
                    "",
                ]
            ),
            encoding="utf-8",
        )
        wrapper_script.chmod(0o755)
        wrapper = subprocess.Popen(
            [str(wrapper_script)],
            cwd=self.root,
            env={"PATH": TEST_ENV["PATH"]},
            text=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        self.processes.append(wrapper)
        for _ in range(20):
            if child_pid_file.exists():
                break
            time.sleep(0.05)
        listener_pid = int(child_pid_file.read_text(encoding="utf-8"))
        (self.pid_dir / "agent-backend.pid").write_text(
            f"{wrapper.pid}\n",
            encoding="utf-8",
        )

        result = self.run_stop_all(listener_pid)
        wrapper.wait(timeout=2)

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(f"stopping recorded pid={wrapper.pid}", result.stdout)

    def test_refuses_to_stop_external_listener(self):
        external_dir = self.root / "external-service"
        external_dir.mkdir()
        process = self.start_process(external_dir)

        result = self.run_stop_all(process.pid)

        self.assertNotEqual(0, result.returncode)
        self.assertIsNone(process.poll())
        self.assertIn("refusing to stop it", result.stderr)

    def test_does_not_kill_process_referenced_by_reused_pid(self):
        external_dir = self.root / "external-service"
        external_dir.mkdir()
        process = self.start_process(external_dir)
        (self.pid_dir / "agent-backend.pid").write_text(
            f"{process.pid}\n",
            encoding="utf-8",
        )

        result = self.run_stop_all(process.pid)

        self.assertNotEqual(0, result.returncode)
        self.assertIsNone(process.poll())
        self.assertFalse((self.pid_dir / "agent-backend.pid").exists())
        self.assertIn("no longer belongs to this repository", result.stderr)


if __name__ == "__main__":
    unittest.main()
