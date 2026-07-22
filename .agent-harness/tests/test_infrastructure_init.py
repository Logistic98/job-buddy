from __future__ import annotations

import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
APPLICATION_COMPOSE = REPO_ROOT / "docker-compose.yml"
INFRASTRUCTURE_COMPOSE = REPO_ROOT / "docker-compose-infra.yml"
ENV_EXAMPLE = REPO_ROOT / ".env.example"
START_ALL = REPO_ROOT / "scripts" / "start-all.sh"


class InfrastructureInitializationTest(unittest.TestCase):
    def test_backend_and_memory_share_application_database(self):
        compose = APPLICATION_COMPOSE.read_text(encoding="utf-8")
        expected_url = "jdbc:postgresql://postgres:5432/${POSTGRES_APP_DB:-job_buddy}"

        self.assertEqual(2, compose.count(expected_url))
        self.assertNotIn("POSTGRES_MEMORY_DB", compose)

    def test_infrastructure_does_not_initialize_separate_memory_database(self):
        compose = INFRASTRUCTURE_COMPOSE.read_text(encoding="utf-8")
        env_example = ENV_EXAMPLE.read_text(encoding="utf-8")

        self.assertNotIn("POSTGRES_MEMORY_DB", compose)
        self.assertNotIn("docker-entrypoint-initdb.d", compose)
        self.assertNotIn("POSTGRES_MEMORY_DB", env_example)

    def test_backend_initializes_shared_schema_before_memory_in_compose(self):
        compose = APPLICATION_COMPOSE.read_text(encoding="utf-8")
        memory_block = compose.split("  agent-memory:", 1)[1].split("\n  agent-tool:", 1)[0]
        backend_service_marker = "\n  agent-backend:\n    <<: *agent-service"
        backend_block = compose.split(backend_service_marker, 1)[1].split("\n  agent-frontend:", 1)[0]

        self.assertIn("depends_on:", memory_block)
        self.assertIn("agent-backend:", memory_block)
        self.assertIn("condition: service_healthy", memory_block)
        self.assertNotIn("depends_on:", backend_block)

    def test_backend_starts_before_memory_in_local_start_script(self):
        start_script = START_ALL.read_text(encoding="utf-8")

        backend_position = start_script.index('start_service "agent-backend"')
        memory_position = start_script.index('start_service "agent-memory"')

        self.assertLess(backend_position, memory_position)
        self.assertIn('${START_ALL_READY_TIMEOUT_SECONDS:-300}', start_script)


if __name__ == "__main__":
    unittest.main()
