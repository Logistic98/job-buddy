"""Run the fixed synthetic baseline with real Mem0 and real configured models."""

import argparse
import hashlib
import importlib.metadata
import json
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "agent-memory"))
sys.path.insert(0, str(ROOT / "agent-eval" / "app"))

from cases import load_suite, memory_dataset  # noqa: E402
from memory_grader import grade_memory  # noqa: E402

from app.env import load_root_dotenv  # noqa: E402
from app.store import MemoryStore, build_memory, database_url  # noqa: E402


def run(store, dataset, report):
    owner = {"tenant_id": report["run_id"], "operator_id": "baseline-owner"}
    other = dict(owner, operator_id="other-user")
    other_tenant = dict(owner, tenant_id=report["run_id"] + "-other")
    public_ids = {}
    try:
        for fact in dataset["facts"]:
            public_ids[fact["id"]] = store.add("long_term", fact["content"], **owner)["id"]
        reverse = {value: key for key, value in public_ids.items()}
        for query in dataset["queries"]:
            start = time.perf_counter()
            found = store.search(query["query"], "long_term", **owner)
            elapsed = (time.perf_counter() - start) * 1000
            report["queries"].append(
                {**query, "found_ids": [reverse.get(row["id"], "unexpected") for row in found], "elapsed_ms": elapsed}
            )
        for case in dataset["updates"]:
            start = time.perf_counter()
            updated = store.update(public_ids[case["id"]], case["content"], **owner)
            elapsed = (time.perf_counter() - start) * 1000
            found = store.search(case["query"], "long_term", **owner)
            original = next(f["content"] for f in dataset["facts"] if f["id"] == case["id"])
            report["updates"].append(
                {
                    "id": case["id"],
                    "case_id": case["case_id"],
                    "elapsed_ms": elapsed,
                    "passed": updated["content"] == case["content"]
                    and any(row["id"] == public_ids[case["id"]] and row["content"] == case["content"] for row in found)
                    and all(row["content"] != original for row in found),
                }
            )
        target = public_ids["city"]
        for identity in [other, other_tenant]:
            checks = {
                "search": not store.search("目标城市", **identity),
                "list": not store.list_items(**identity),
                "update": store.update(target, "unauthorized", **identity) is None,
                "rollback": store.rollback(target, **identity) is None,
                "delete": not store.delete(target, **identity),
                "clear": store.clear(**identity) == 0,
            }
            report["isolation"].extend({"operation": key, "passed": value} for key, value in checks.items())
        rolled = store.rollback(target, **owner)
        report["lifecycle"].append(
            {"operation": "rollback", "passed": rolled["content"] == dataset["facts"][0]["content"]}
        )
        hidden = store.add("disabled", "禁用测试记忆", enabled=False, **owner)
        expired = store.add("expired", "过期测试记忆", ttl_seconds=1, **owner)
        time.sleep(1.1)
        report["lifecycle"].extend(
            [
                {"operation": "disabled", "passed": not store.search("禁用测试记忆", "disabled", **owner)},
                {"operation": "ttl", "passed": not store.search("过期测试记忆", "expired", **owner)},
                {"operation": "purge", "passed": store.purge_expired(**owner) == 1},
                {
                    "operation": "delete",
                    "passed": store.delete(hidden["id"], **owner) and not store.list_items("disabled", **owner),
                },
                {"operation": "expired_update", "passed": store.update(expired["id"], "revive", **owner) is None},
            ]
        )
    finally:
        store.clear(**owner)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--backend", choices=["pgvector", "qdrant"], default="pgvector")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    load_root_dotenv()
    path = ROOT / "agent-eval/cases/memory-baseline.yaml"
    dataset = memory_dataset(load_suite(path, kind="memory"))
    report = {
        "run_id": "memory-eval-" + uuid4().hex,
        "mode": "live",
        "backend": args.backend,
        "mem0_version": importlib.metadata.version("mem0ai"),
        "dataset_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "git_sha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "queries": [],
        "updates": [],
        "isolation": [],
        "lifecycle": [],
        "errors": [],
        "passed": False,
    }
    report["thresholds"] = dataset["thresholds"]
    report["dataset_version"] = dataset["version"]
    report["source_sha256"] = {
        str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in [
            ROOT / "agent-memory/app/store.py",
            ROOT / "agent-memory/scripts/evaluate_memory.py",
            ROOT / "agent-memory/uv.lock",
            ROOT / "agent-eval/app/memory_grader.py",
        ]
    }
    report["python_version"] = sys.version.split()[0]
    report["concurrency"] = 1
    try:
        with tempfile.TemporaryDirectory(prefix="memory-eval-") as temp:
            import os

            dims = int(os.environ["AGENT_MEMORY_EMBEDDING_DIMS"])
            vector = (
                {"provider": "qdrant", "config": {"path": ":memory:", "embedding_model_dims": dims}}
                if args.backend == "qdrant"
                else {
                    "provider": "pgvector",
                    "config": {
                        "connection_string": database_url(),
                        "collection_name": "memory_eval_" + uuid4().hex,
                        "embedding_model_dims": dims,
                        "sslmode": os.getenv("AGENT_MEMORY_DB_SSL_MODE", "require"),
                    },
                }
            )
            engine = build_memory(vector_config=vector, history_path=str(Path(temp) / "history.db"))
            store = MemoryStore(engine)
            report["embedding_model"] = engine.embedding_model.config.model
            report["llm_model"] = engine.llm.config.model
            report["extraction_evaluated"] = False
            report["ranking"] = "mem0 native; optional nlp/fastembed not installed"
            try:
                run(store, dataset, report)
                report.update(grade_memory(report, dataset["thresholds"]))
            finally:
                if args.backend == "pgvector":
                    engine.vector_store.delete_col()
                store.close()
    except Exception as exc:
        import traceback

        report["errors"].append(
            {
                "type": type(exc).__name__,
                "frames": [
                    {"file": Path(frame.filename).name, "line": frame.lineno, "function": frame.name}
                    for frame in traceback.extract_tb(exc.__traceback__)
                ],
            }
        )
        report["passed"] = False
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x") as output:
        json.dump(report, output, ensure_ascii=False, indent=2)
        output.write("\n")
    print(
        json.dumps(
            {
                "passed": report["passed"],
                "metrics": report.get("metrics"),
                "errors": report["errors"],
                "report": str(args.output),
            },
            ensure_ascii=False,
        )
    )
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
