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
        return MODULE.Migration(path, name, match.group("version"))

    def validate(self, sql: str, name: str = "V3_4_5__Alter_business_schema.sql"):
        return MODULE.validate_migration_policy([self.migration(name, sql)])

    def test_allows_schema_evolution_without_version_specific_rules(self):
        errors = self.validate(
            "ALTER TABLE project_deep_dive_project ADD COLUMN project_type VARCHAR(128);"
        )
        self.assertEqual([], errors)

    def test_allows_shared_system_data(self):
        errors = self.validate(
            "INSERT INTO rbac_menu(menu_id) VALUES ('menu_future') "
            "ON CONFLICT (menu_id) DO UPDATE SET display_order = 1;\n"
            "UPDATE permission_definition SET grantable = TRUE WHERE permission_code = 'resume:use';",
            name="V8_2_1__Update_authorization_catalog.sql",
        )
        self.assertEqual([], errors)

    def test_allows_controlled_default_identity_data(self):
        errors = self.validate(
            "INSERT INTO app_user(user_id, username) VALUES ('job_buddy_admin', 'admin');\n"
            "DELETE FROM user_login_session WHERE user_id = 'job_buddy_admin';",
            name="V2_7_3__Update_default_identity.sql",
        )
        self.assertEqual([], errors)

    def test_rejects_unscoped_identity_data(self):
        for sql in (
            "INSERT INTO app_user(user_id) VALUES ('another-user');",
            "UPDATE app_user SET enabled = FALSE;",
            "DELETE FROM user_role WHERE tenant_id = 'default-tenant';",
        ):
            with self.subTest(sql=sql):
                errors = self.validate(sql, name="V6_1_2__Update_identity_data.sql")
                self.assertTrue(any("non-system table" in error for error in errors))

    def test_rejects_private_business_data(self):
        for sql in (
            "INSERT INTO project_deep_dive_project(id) VALUES ('private');",
            "UPDATE resume_record SET tenant_id = 'default';",
            "DELETE FROM job_favorite WHERE user_id = 'user-1';",
        ):
            with self.subTest(sql=sql):
                errors = self.validate(sql, name="V9_5_4__Update_business_data.sql")
                self.assertTrue(any("is forbidden" in error for error in errors))

    def test_rejects_non_action_description_prefix(self):
        path = self.root / "V2_3_4__Seed_default_users.sql"
        path.write_text("INSERT INTO app_user(user_id) VALUES ('user');", encoding="utf-8")
        _, errors = MODULE.collect(self.root, self.root)
        self.assertTrue(any("SQL action verb" in error for error in errors))

    def test_rejects_duplicate_versions(self):
        (self.root / "V4_5_6__Create_first_table.sql").write_text("", encoding="utf-8")
        (self.root / "V4_5_6__Alter_second_table.sql").write_text("", encoding="utf-8")
        _, errors = MODULE.collect(self.root, self.root)
        self.assertTrue(any("duplicate Flyway version" in error for error in errors))

    def test_rejects_leading_zero_version_segments(self):
        path = self.root / "V4_05_6__Create_table.sql"
        path.write_text("", encoding="utf-8")
        _, errors = MODULE.collect(self.root, self.root)
        self.assertTrue(any("leading zeroes" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
