from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
PRE_COMMIT_CHECK = REPO_ROOT / ".agent-harness" / "scripts" / "pre-commit-hook.sh"


class PreCommitHookTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        scripts_dir = self.root / ".agent-harness" / "scripts"
        scripts_dir.mkdir(parents=True)
        shutil.copy2(PRE_COMMIT_CHECK, scripts_dir / "pre-commit-hook.sh")

        self.verify_log = self.root / "verify.log"
        verify_script = scripts_dir / "verify.sh"
        verify_script.write_text(
            "\n".join(
                [
                    "#!/usr/bin/env bash",
                    "set -euo pipefail",
                    'if [[ "${1:-}" == "--list" ]]; then',
                    "  printf '%s\\n' agent-frontend agent-runtime",
                    "  exit 0",
                    "fi",
                    'printf "%s\\n" "$*" >> "$VERIFY_LOG"',
                    "",
                ]
            ),
            encoding="utf-8",
        )
        verify_script.chmod(0o755)

        subprocess.run(["git", "init", "-q"], cwd=self.root, check=True)
        subprocess.run(
            ["git", "config", "user.email", "harness@example.com"],
            cwd=self.root,
            check=True,
        )
        subprocess.run(
            ["git", "config", "user.name", "Harness Test"],
            cwd=self.root,
            check=True,
        )

    def tearDown(self):
        self.temp_dir.cleanup()

    def run_hook(self) -> subprocess.CompletedProcess[str]:
        env = os.environ.copy()
        env["VERIFY_LOG"] = str(self.verify_log)
        return subprocess.run(
            ["bash", ".agent-harness/scripts/pre-commit-hook.sh"],
            cwd=self.root,
            env=env,
            text=True,
            capture_output=True,
            check=False,
        )

    def stage(self, relative_path: str, content: str = "content\n") -> None:
        path = self.root / relative_path
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        subprocess.run(["git", "add", relative_path], cwd=self.root, check=True)

    def test_verifies_only_the_changed_module(self):
        self.stage("agent-frontend/src/app.js")

        result = self.run_hook()

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("agent-frontend --quick\n", self.verify_log.read_text())

    def test_shared_script_change_runs_repository_verification(self):
        self.stage("scripts/build.sh", "#!/usr/bin/env bash\n")

        result = self.run_hook()

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("--quick\n", self.verify_log.read_text())

    def test_rejects_staged_whitespace_errors_before_verification(self):
        self.stage("agent-runtime/app.py", "value = 1   \n")

        result = self.run_hook()

        self.assertNotEqual(0, result.returncode)
        self.assertIn("trailing whitespace", result.stdout)
        self.assertFalse(self.verify_log.exists())


if __name__ == "__main__":
    unittest.main()
