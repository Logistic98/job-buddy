import hashlib
import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT_PATH = Path(__file__).resolve().parents[1] / "scripts" / "check_flyway_migrations.py"
SPEC = importlib.util.spec_from_file_location("check_flyway_migrations", SCRIPT_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class FlywayPolicyTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)

    def tearDown(self):
        self.temp_dir.cleanup()

    def migration(self, name: str, sql: str = ""):
        path = self.root / name
        path.write_text(sql, encoding="utf-8")
        match = MODULE.MIGRATION_RE.match(name)
        assert match
        version_text = match.group("version")
        return MODULE.Migration(path, name, version_text, MODULE.parse_version(version_text))

    def canonical_migrations(self):
        return [self.migration(name) for name in sorted(MODULE.CANONICAL_BASELINE)]

    def validate_future(self, sql: str, name: str = "V1_0_8__Insert_test_data.sql"):
        migrations = self.canonical_migrations()
        migrations.append(self.migration(name, sql))
        return MODULE.validate_canonical_baseline(migrations)

    def test_rejects_schema_evolution_inside_canonical_baseline(self):
        migrations = self.canonical_migrations()
        migrations[0].path.write_text("ALTER TABLE tenant ADD COLUMN extra_name TEXT;", encoding="utf-8")
        errors = MODULE.validate_canonical_baseline(migrations)
        self.assertTrue(any("must not ALTER/RENAME/DROP" in error for error in errors))

    def test_baseline_data_allows_catalog_and_default_identity_inserts(self):
        migrations = self.canonical_migrations()
        data_migration = next(item for item in migrations if item.version == MODULE.BASELINE_MAX_VERSION)
        data_migration.path.write_text(
            "INSERT INTO rbac_menu(menu_id) VALUES ('menu_test');\n"
            "INSERT INTO app_user(user_id) VALUES ('job_buddy_admin');\n"
            "INSERT INTO user_role(tenant_id, user_id, role_id) "
            "VALUES ('default-tenant', 'job_buddy_admin', 'role_admin');",
            encoding="utf-8",
        )
        self.assertEqual([], MODULE.validate_canonical_baseline(migrations))
        data_migration.path.write_text("INSERT INTO auth_state(provider) VALUES ('private');", encoding="utf-8")
        errors = MODULE.validate_canonical_baseline(migrations)
        self.assertTrue(any("forbidden tables" in error for error in errors))

    def test_rejects_private_insert_after_baseline(self):
        for table in ("project_deep_dive_project", "profile_document", "interview_question", "analysis_task"):
            with self.subTest(table=table):
                errors = self.validate_future(f"INSERT INTO {table}(id) VALUES ('private');")
                self.assertTrue(any(f"private table {table}" in error for error in errors))

    def test_allows_controlled_system_blacklist_insert_after_baseline(self):
        errors = self.validate_future(
            "INSERT INTO blacklist_item(item_id, name, item_type, source) "
            "VALUES ('system-keyword-od', 'OD', 'keyword', 'system');",
            name="V1_0_8__Insert_default_job_blacklist.sql",
        )
        self.assertEqual([], errors)

    def test_rejects_blacklist_insert_from_unregistered_migration(self):
        errors = self.validate_future(
            "INSERT INTO blacklist_item(item_id, name, item_type, source) "
            "VALUES ('manual-keyword', 'manual', 'keyword', 'manual');"
        )
        self.assertTrue(any("private table blacklist_item" in error for error in errors))

    def test_rejects_private_update_and_delete_after_baseline(self):
        for sql in (
            "UPDATE resume_record SET tenant_id = 'default';",
            "DELETE FROM job_favorite WHERE user_id = 'user-1';",
            "UPDATE blacklist_item SET enabled = FALSE;",
            "DELETE FROM blacklist_item WHERE source = 'system';",
        ):
            with self.subTest(sql=sql):
                errors = self.validate_future(sql)
                self.assertTrue(any("is forbidden" in error for error in errors))

    def test_rejects_identity_changes_after_baseline(self):
        for sql in (
            "INSERT INTO app_user(user_id) VALUES ('another-user');",
            "UPDATE app_user SET enabled = FALSE;",
            "DELETE FROM user_role WHERE user_id = 'job_buddy_admin';",
        ):
            with self.subTest(sql=sql):
                errors = self.validate_future(sql)
                self.assertTrue(any("is forbidden" in error for error in errors))

    def test_rejects_non_action_description_prefix(self):
        path = self.root / "V1_0_8__Seed_default_users.sql"
        path.write_text("INSERT INTO app_user(user_id) VALUES ('user');", encoding="utf-8")
        _, errors = MODULE.collect(self.root, self.root)
        self.assertTrue(any("SQL action verb" in error for error in errors))

    def test_allows_shared_insert_and_private_schema_change_after_baseline(self):
        self.assertEqual([], self.validate_future(
            "INSERT INTO rbac_menu(menu_id) VALUES ('menu_future');\n"
            "ALTER TABLE project_deep_dive_project ADD COLUMN project_type VARCHAR(128);"
        ))

    def test_rejects_canonical_baseline_checksum_changes(self):
        migrations = self.canonical_migrations()
        checksum_file = self.root / "flyway-baseline.sha256"
        checksum_file.write_text("\n".join(
            f"{hashlib.sha256(migration.path.read_bytes()).hexdigest()}  {migration.path.name}"
            for migration in migrations
        ) + "\n", encoding="utf-8")
        manifest_digest = hashlib.sha256(checksum_file.read_bytes()).hexdigest()
        self.assertEqual([], MODULE.validate_baseline_checksums(migrations, checksum_file, manifest_digest))

        migrations[0].path.write_text("CREATE TABLE changed(id BIGINT);", encoding="utf-8")
        errors = MODULE.validate_baseline_checksums(migrations, checksum_file, manifest_digest)
        self.assertTrue(any("checksum mismatch" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
