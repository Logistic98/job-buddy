import os
import re
import subprocess
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / ".agent-harness" / "scripts" / "collect_run_metadata.sh"


class RunMetadataTest(unittest.TestCase):
    def test_metadata_is_traceable_without_exposing_environment_values(self):
        secret_marker = "metadata-secret-must-not-leak"
        environment = os.environ.copy()
        environment.update(
            {
                "JAVA_TOOL_OPTIONS": f"-Dtoken={secret_marker}",
                "JDK_JAVA_OPTIONS": f"-Dpassword={secret_marker}",
                "_JAVA_OPTIONS": f"-Dsecret={secret_marker}",
                "MAVEN_OPTS": f"-Dcredential={secret_marker}",
            }
        )
        result = subprocess.run(
            ["bash", str(SCRIPT)],
            cwd=REPO_ROOT,
            check=True,
            capture_output=True,
            text=True,
            env=environment,
        )
        expected_sha = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=REPO_ROOT,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()

        self.assertIn("## Run metadata", result.stdout)
        self.assertIn(f"- git_sha: {expected_sha}", result.stdout)
        self.assertRegex(result.stdout, r"- git_dirty: (?:true|false)")
        self.assertRegex(result.stdout, r"- os: \S+")
        self.assertRegex(result.stdout, r"- cpu_count: (?:\d+|unavailable)")
        self.assertIn("- live_model: not_used_by_deterministic_gate", result.stdout)
        self.assertIn("### Dependency manifests", result.stdout)
        self.assertRegex(
            result.stdout,
            re.compile(r"- agent-frontend/package-lock\.json: [0-9a-f]{64}"),
        )
        self.assertNotIn("API_KEY=", result.stdout)
        self.assertNotIn("PASSWORD=", result.stdout)
        self.assertNotIn(secret_marker, result.stdout)
        self.assertNotIn(secret_marker, result.stderr)


if __name__ == "__main__":
    unittest.main()
