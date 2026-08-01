from __future__ import annotations

import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
APPLICATION_COMPOSE = REPO_ROOT / "docker-compose.yml"
INFRASTRUCTURE_COMPOSE = REPO_ROOT / "docker-compose-infra.yml"
ENV_EXAMPLE = REPO_ROOT / ".env.example"
START_ALL = REPO_ROOT / "scripts" / "start-all.sh"
BACKEND_DOCKERFILE = REPO_ROOT / "agent-backend" / "Dockerfile"
FRONTEND_DOCKERFILE = REPO_ROOT / "agent-frontend" / "Dockerfile"
SANDBOX_DOCKERFILE = REPO_ROOT / "agent-sandbox" / "Dockerfile"
TOOL_DOCKERFILE = REPO_ROOT / "agent-tool" / "Dockerfile"
PYTHON_DOCKERFILES = tuple(
    REPO_ROOT / module / "Dockerfile"
    for module in (
        "agent-runtime",
        "agent-intent",
        "agent-tool",
        "agent-sandbox",
        "agent-eval",
        "agent-memory",
    )
)


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

    def test_application_dockerfiles_reuse_dependency_download_caches(self):
        backend_dockerfile = BACKEND_DOCKERFILE.read_text(encoding="utf-8")
        frontend_dockerfile = FRONTEND_DOCKERFILE.read_text(encoding="utf-8")

        self.assertIn("target=/root/.m2", backend_dockerfile)
        self.assertIn("target=/root/.npm", frontend_dockerfile)
        for dockerfile_path in PYTHON_DOCKERFILES:
            with self.subTest(dockerfile=dockerfile_path.parent.name):
                dockerfile = dockerfile_path.read_text(encoding="utf-8")
                self.assertTrue(dockerfile.startswith("# syntax=docker/dockerfile:1.7\n"))
                self.assertIn("target=/root/.cache/pip", dockerfile)
                self.assertIn("target=/root/.cache/uv", dockerfile)
                self.assertIn("UV_LINK_MODE=copy", dockerfile)

    def test_frontend_dockerfile_includes_public_build_assets(self):
        dockerfile = FRONTEND_DOCKERFILE.read_text(encoding="utf-8")

        self.assertIn("COPY public ./public", dockerfile)

    def test_tool_browser_layer_is_independent_from_application_sources(self):
        dockerfile = TOOL_DOCKERFILE.read_text(encoding="utf-8")

        browser_install_position = dockerfile.index("python -m playwright install")
        self.assertLess(browser_install_position, dockerfile.index("COPY app ./app"))
        self.assertLess(browser_install_position, dockerfile.index("COPY server.py ./"))

    def test_sandbox_compose_is_portable_and_uses_bounded_readiness(self):
        compose = APPLICATION_COMPOSE.read_text(encoding="utf-8")
        dockerfile = SANDBOX_DOCKERFILE.read_text(encoding="utf-8")
        env_example = ENV_EXAMPLE.read_text(encoding="utf-8")
        sandbox_block = compose.split("  agent-sandbox:", 1)[1].split(
            "\n  agent-memory:", 1
        )[0]

        self.assertIn("apparmor=unconfined", compose)
        self.assertIn("init: true", sandbox_block)
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

    def test_memory_uses_self_hosted_storage_without_external_gateway(self):
        env_example = ENV_EXAMPLE.read_text(encoding="utf-8")

        self.assertNotIn("TDAI_MEMORY_GATEWAY", env_example)
        self.assertNotIn("TDAI_GATEWAY_API_KEY", env_example)
        self.assertFalse(
            (REPO_ROOT / "agent-memory" / "app" / "tencentdb_adapter.py").exists()
        )

    def test_retrieval_model_secrets_are_scoped_to_memory_service(self):
        compose = APPLICATION_COMPOSE.read_text(encoding="utf-8")
        memory_block = compose.split("  agent-memory:", 1)[1].split(
            "\n  agent-tool:", 1
        )[0]

        self.assertIn(
            "AGENT_MEMORY_EMBEDDING_API_KEY: ${AGENT_MEMORY_EMBEDDING_API_KEY:-}",
            memory_block,
        )
        self.assertIn(
            "AGENT_MEMORY_RERANK_API_KEY: ${AGENT_MEMORY_RERANK_API_KEY:-}",
            memory_block,
        )
        non_memory_blocks = compose.replace(memory_block, "")
        self.assertNotIn("AGENT_MEMORY_EMBEDDING_API_KEY", non_memory_blocks)
        self.assertNotIn("AGENT_MEMORY_RERANK_API_KEY", non_memory_blocks)


if __name__ == "__main__":
    unittest.main()
