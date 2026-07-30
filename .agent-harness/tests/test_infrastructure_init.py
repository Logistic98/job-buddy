from __future__ import annotations

import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
APPLICATION_COMPOSE = REPO_ROOT / "docker-compose.yml"
INFRASTRUCTURE_COMPOSE = REPO_ROOT / "docker-compose-infra.yml"
ENV_EXAMPLE = REPO_ROOT / ".env.example"
START_ALL = REPO_ROOT / "scripts" / "start-all.sh"
BACKEND_DOCKERFILE = REPO_ROOT / "agent-backend" / "Dockerfile"
SANDBOX_DOCKERFILE = REPO_ROOT / "agent-sandbox" / "Dockerfile"


class InfrastructureInitializationTest(unittest.TestCase):
    def test_application_compose_uses_configured_external_infrastructure(self):
        compose = APPLICATION_COMPOSE.read_text(encoding="utf-8")

        self.assertIn("SPRING_DATASOURCE_URL: ${SPRING_DATASOURCE_URL:?", compose)
        self.assertIn("SPRING_REDIS_HOST: ${SPRING_REDIS_HOST:?", compose)
        self.assertIn("JOB_BUDDY_MINIO_ENDPOINT: ${JOB_BUDDY_MINIO_ENDPOINT:?", compose)
        self.assertIn("AGENT_RUNTIME_DATABASE_URL: ${AGENT_RUNTIME_DATABASE_URL:?", compose)
        self.assertIn("AGENT_MEMORY_DATABASE_URL: ${AGENT_MEMORY_DATABASE_URL:?", compose)
        self.assertNotIn("external: true", compose)
        self.assertNotIn("POSTGRES_MEMORY_DB", compose)
        for service in ("INTENT", "MEMORY", "TOOL", "EVAL", "RUNTIME", "SANDBOX"):
            self.assertIn(f"JOB_BUDDY_INTERNAL_{service}_URL", compose)

    def test_full_compose_uses_one_postgres_database(self):
        compose = INFRASTRUCTURE_COMPOSE.read_text(encoding="utf-8")
        env_example = ENV_EXAMPLE.read_text(encoding="utf-8")
        expected_url = "jdbc:postgresql://postgres:5432/${POSTGRES_APP_DB:-job_buddy}"

        self.assertEqual(2, compose.count(expected_url))
        self.assertNotIn("POSTGRES_MEMORY_DB", compose)
        self.assertNotIn("docker-entrypoint-initdb.d", compose)
        self.assertNotIn("POSTGRES_MEMORY_DB", env_example)

    def test_full_compose_waits_for_infrastructure_and_backend_schema(self):
        application_compose = APPLICATION_COMPOSE.read_text(encoding="utf-8")
        infrastructure_compose = INFRASTRUCTURE_COMPOSE.read_text(encoding="utf-8")
        memory_block = application_compose.split("  agent-memory:", 1)[1].split(
            "\n  agent-tool:", 1
        )[0]
        backend_service_marker = "\n  agent-backend:\n    <<: *agent-service"
        backend_block = application_compose.split(backend_service_marker, 1)[1].split(
            "\n  agent-frontend:", 1
        )[0]
        infrastructure_backend_block = infrastructure_compose.split(
            "\n  agent-backend:", 1
        )[1].split("\nvolumes:", 1)[0]

        self.assertIn("depends_on:", memory_block)
        self.assertIn("agent-backend:", memory_block)
        self.assertIn("condition: service_healthy", memory_block)
        self.assertNotIn("depends_on:", backend_block)
        for service in ("postgres:", "redis:", "minio:"):
            self.assertIn(service, infrastructure_backend_block)

    def test_backend_starts_before_memory_in_local_start_script(self):
        start_script = START_ALL.read_text(encoding="utf-8")

        backend_position = start_script.index('start_service "agent-backend"')
        memory_position = start_script.index('start_service "agent-memory"')

        self.assertLess(backend_position, memory_position)
        self.assertIn('${START_ALL_READY_TIMEOUT_SECONDS:-300}', start_script)

    def test_backend_container_allows_for_slow_server_startup(self):
        dockerfile = BACKEND_DOCKERFILE.read_text(encoding="utf-8")

        self.assertIn("--start-period=300s", dockerfile)

    def test_sandbox_compose_is_portable_and_uses_bounded_readiness(self):
        compose = APPLICATION_COMPOSE.read_text(encoding="utf-8")
        dockerfile = SANDBOX_DOCKERFILE.read_text(encoding="utf-8")
        env_example = ENV_EXAMPLE.read_text(encoding="utf-8")

        self.assertIn("apparmor=unconfined", compose)
        self.assertNotIn("AGENT_SANDBOX_CONTAINER_APPARMOR_PROFILE", compose)
        self.assertIn("AGENT_SANDBOX_READINESS_TIMEOUT_SECONDS", compose)
        self.assertIn("AGENT_SANDBOX_CONTAINER_HEALTH_TIMEOUT", compose)
        self.assertIn("--timeout=20s", dockerfile)
        self.assertIn("--start-period=60s", dockerfile)
        self.assertNotIn("AGENT_SANDBOX_CONTAINER_APPARMOR_PROFILE", env_example)
        self.assertFalse(
            (REPO_ROOT / "scripts" / "install-sandbox-apparmor.sh").exists()
        )
        self.assertFalse((REPO_ROOT / "agent-sandbox" / "apparmor").exists())


if __name__ == "__main__":
    unittest.main()
