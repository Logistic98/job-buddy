package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Set;
import java.util.TreeSet;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class CanonicalBaselinePostgresTest {

  private static final Set<String> EXPECTED_TABLES =
      Set.of(
          "analysis_task",
          "app_user",
          "auth_state",
          "blacklist_item",
          "boss_qr_login_session",
          "chat_message_log",
          "chat_session_state",
          "interview_exam",
          "interview_exam_question",
          "interview_question",
          "job_favorite",
          "journey_record",
          "journey_target",
          "permission_definition",
          "platform_setting",
          "project_deep_dive_material",
          "project_deep_dive_project",
          "project_deep_dive_question",
          "rbac_menu",
          "rbac_role",
          "resume_asset",
          "resume_record",
          "resume_writer_version",
          "role_menu",
          "tenant",
          "user_login_session",
          "user_role",
          "user_workspace_state");

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @BeforeEach
  void resetDatabase() throws Exception {
    try (Connection connection = connection();
        Statement statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA public CASCADE");
      statement.execute("CREATE SCHEMA public");
    }
  }

  @Test
  void emptyDatabaseMigratesWithDefaultUsersAuthorizationAndJobBlacklist() throws Exception {
    var result = flyway().migrate();

    assertEquals(14, result.migrationsExecuted);
    assertEquals("1.0.13", result.targetSchemaVersion);
    assertEquals(EXPECTED_TABLES, applicationTables());
    assertEquals(
        266,
        queryLong(
            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = 'public' "
                + "AND table_name <> 'flyway_schema_history'"));
    assertEquals(2, queryLong("SELECT COUNT(*) FROM app_user"));
    assertEquals(2, queryLong("SELECT COUNT(*) FROM user_role"));
    assertEquals(
        1,
        queryLong(
            "SELECT COUNT(*) FROM user_role WHERE user_id = 'job_buddy_admin' AND role_id ="
                + " 'role_admin'"));
    assertEquals(
        1,
        queryLong(
            "SELECT COUNT(*) FROM user_role WHERE user_id = 'job_buddy_user' AND role_id ="
                + " 'role_user'"));
    assertEquals(
        "admin", queryString("SELECT username FROM app_user WHERE user_id = 'job_buddy_admin'"));
    assertEquals(
        "user", queryString("SELECT username FROM app_user WHERE user_id = 'job_buddy_user'"));
    assertTrue(queryBoolean("SELECT enabled FROM app_user WHERE user_id = 'job_buddy_admin'"));
    assertTrue(queryBoolean("SELECT enabled FROM app_user WHERE user_id = 'job_buddy_user'"));
    assertEquals(10, queryLong("SELECT COUNT(*) FROM permission_definition"));
    assertEquals(2, queryLong("SELECT COUNT(*) FROM rbac_role"));
    assertEquals(15, queryLong("SELECT COUNT(*) FROM rbac_menu"));
    assertEquals(22, queryLong("SELECT COUNT(*) FROM role_menu"));
    assertEquals(
        7,
        queryLong(
            "SELECT COUNT(*) FROM role_menu WHERE role_id = 'role_admin' "
                + "AND menu_id IN ('menu_settings_users', 'menu_settings_roles', "
                + "'menu_settings_menus', 'menu_settings_tenant', 'menu_settings_platform', "
                + "'menu_settings_memory', 'menu_settings_services')"));
    assertEquals(
        0,
        queryLong(
            "SELECT COUNT(*) FROM role_menu WHERE role_id = 'role_user' "
                + "AND menu_id IN ('menu_settings', 'menu_settings_tenant', "
                + "'menu_settings_platform', 'menu_settings_memory', 'menu_settings_services')"));
    assertEquals(
        0,
        queryLong(
            "SELECT COUNT(*) FROM rbac_menu WHERE menu_type = 'action' "
                + "OR menu_code = 'chat-boss'"));
    assertEquals(
        0,
        queryLong("SELECT COUNT(*) FROM permission_definition WHERE permission_code = 'boss:use'"));
    assertEquals(47, queryLong("SELECT COUNT(*) FROM blacklist_item"));
    assertTrue(tableExists("idx_app_user_tenant_created_username"));
    assertTrue(tableExists("idx_rbac_role_tenant_created_name"));
    assertTrue(tableExists("uk_chat_message_user_turn"));
    assertEquals(
        40,
        queryLong(
            "SELECT COUNT(*) FROM blacklist_item WHERE item_type = 'company' AND source ="
                + " 'system'"));
    assertEquals(
        7,
        queryLong(
            "SELECT COUNT(*) FROM blacklist_item WHERE item_type = 'keyword' AND source ="
                + " 'system'"));
    assertEquals(
        1,
        queryLong(
            "SELECT COUNT(*) FROM blacklist_item WHERE name = 'OD' AND item_type = 'keyword'"));
    assertFalse(tableExists("agent_tool_result"));
    assertTrue(tableExists("agent_run_checkpoint"));
    assertFalse(tableExists("project_asset"));
    assertFalse(tableExists("profile_document"));
    assertFalse(tableExists("user_permission"));
    assertEquals(
        0,
        queryLong(
            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = 'public' AND"
                + " (column_name IN ('source_hash', 'folder_name', 'version_label',"
                + " 'tenant_name', 'last_seen_at', 'favorite_id', 'evaluated_at'))"));
  }

  @Test
  void nonEmptySchemaWithoutFlywayHistoryFailsClosed() throws Exception {
    try (Connection connection = connection();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE unmanaged_table(id BIGINT PRIMARY KEY)");
    }

    FlywayException error = assertThrows(FlywayException.class, () -> flyway().migrate());
    assertTrue(
        error.getMessage().contains("non-empty schema") || error.getMessage().contains("baseline"));
    assertFalse(tableExists("tenant"));
  }

  private Flyway flyway() {
    return Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .baselineOnMigrate(false)
        .placeholderReplacement(false)
        .load();
  }

  private Connection connection() throws Exception {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private Set<String> applicationTables() throws Exception {
    Set<String> tables = new TreeSet<>();
    try (Connection connection = connection();
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT table_name FROM information_schema.tables "
                    + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE' "
                    + "AND table_name <> 'flyway_schema_history'")) {
      while (rows.next()) {
        tables.add(rows.getString(1));
      }
    }
    return tables;
  }

  private boolean tableExists(String table) throws Exception {
    try (Connection connection = connection();
        var statement = connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
      statement.setString(1, "public." + table);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getBoolean(1);
      }
    }
  }

  private long queryLong(String sql) throws Exception {
    try (Connection connection = connection();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getLong(1);
    }
  }

  private boolean queryBoolean(String sql) throws Exception {
    try (Connection connection = connection();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getBoolean(1);
    }
  }

  private String queryString(String sql) throws Exception {
    try (Connection connection = connection();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getString(1);
    }
  }
}
