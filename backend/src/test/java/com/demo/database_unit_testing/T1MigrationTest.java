package com.demo.database_unit_testing;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class T1MigrationTest {

  @Container
  static final PostgreSQLContainer<?> postgresContainer =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("testdb")
          .withUsername("testuser")
          .withPassword("testpass");

  private static MigrateResult migrateResult;

  @BeforeAll
  static void migrateDatabase() {
    Flyway flyway =
        Flyway.configure()
            .dataSource(
                postgresContainer.getJdbcUrl(),
                postgresContainer.getUsername(),
                postgresContainer.getPassword())
            .load();
    migrateResult = flyway.migrate();
  }

  private Connection openConnection() throws SQLException {
    return DriverManager.getConnection(
        postgresContainer.getJdbcUrl(),
        postgresContainer.getUsername(),
        postgresContainer.getPassword());
  }

  private boolean exists(String sql, String name) throws SQLException {
    try (Connection connection = openConnection();
        var statement = connection.prepareStatement(sql)) {
      statement.setString(1, name);
      try (ResultSet result = statement.executeQuery()) {
        return result.next();
      }
    }
  }

  @Test
  @DisplayName("Migration creates all four application tables")
  void migrationCreatesAllTables() throws SQLException {
    String sql =
        "SELECT table_name FROM information_schema.tables"
            + " WHERE table_schema = 'public' AND table_name = ?";

    Set<String> present = new HashSet<>();
    for (String table : new String[] {"users", "roles", "user_info", "edit_history"}) {
      if (exists(sql, table)) {
        present.add(table);
      }
    }

    assertAll(
        () -> assertTrue(present.contains("users"), "users missing"),
        () -> assertTrue(present.contains("roles"), "roles missing"),
        () -> assertTrue(present.contains("user_info"), "user_info missing"),
        () -> assertTrue(present.contains("edit_history"), "edit_history missing"));
  }

  @Test
  @DisplayName("Migration creates the four foreign keys")
  void migrationCreatesForeignKeys() throws SQLException {
    String sql =
        "SELECT constraint_name FROM information_schema.table_constraints"
            + " WHERE constraint_schema = 'public'"
            + " AND constraint_type = 'FOREIGN KEY'"
            + " AND constraint_name = ?";

    assertAll(
        () -> assertTrue(exists(sql, "fk_user_info_user"), "fk_user_info_user missing"),
        () -> assertTrue(exists(sql, "fk_user_info_role"), "fk_user_info_role missing"),
        () -> assertTrue(exists(sql, "fk_edit_history_actor"), "fk_edit_history_actor missing"),
        () -> assertTrue(exists(sql, "fk_edit_history_target"), "fk_edit_history_target missing"));
  }

  @Test
  @DisplayName("Migration creates the two history indexes")
  void migrationCreatesIndexes() throws SQLException {
    String sql =
        "SELECT indexname FROM pg_indexes"
            + " WHERE schemaname = 'public' AND indexname = ?";

    assertAll(
        () -> assertTrue(exists(sql, "idx_edit_history_actor_time"), "actor index missing"),
        () -> assertTrue(exists(sql, "idx_edit_history_target_time"), "target index missing"));
  }

  // This test is a sanity check to ensure that the exists() helper method works correctly.
  @Test
  @DisplayName("A table that was never created is reported as absent")
  void absentTableIsDetected() throws SQLException {
    String sql =
        "SELECT table_name FROM information_schema.tables"
            + " WHERE table_schema = 'public' AND table_name = ?";

    assertFalse(exists(sql, "does_not_exist"), "helper must return false for a missing table");
  }
}
