package com.demo;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ConnectionTest {
  @Container
  static final PostgreSQLContainer<?> postgresContainer =
      new PostgreSQLContainer<>("postgres:14")
          .withDatabaseName("testdb")
          .withUsername("testuser")
          .withPassword("testpass");

  @BeforeAll
  static void migrateDatabase() {
    Flyway flyway =
        Flyway.configure()
            .dataSource(
                postgresContainer.getJdbcUrl(),
                postgresContainer.getUsername(),
                postgresContainer.getPassword())
            .load();
    flyway.migrate();
  }

  // Helper Function
  private Connection openConnection() throws Exception {
    return DriverManager.getConnection(
        postgresContainer.getJdbcUrl(),
        postgresContainer.getUsername(),
        postgresContainer.getPassword());
  }

  // Helper Function
  private boolean exists(String sql, String name) throws SQLException {
    try (Connection connection = openConnection();
      PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, name);

        try (ResultSet result = statement.executeQuery()) {
          return result.next();
      }
    } catch (Exception e) {
      return false;
    }
  }

  @Test
  void migrationCreatesAllTables() throws Exception {
    try (Connection connection =
        DriverManager.getConnection(
            postgresContainer.getJdbcUrl(),
            postgresContainer.getUsername(),
            postgresContainer.getPassword())) {

      String sql =
          "SELECT table_name "
              + "FROM information_schema.tables "
              + "WHERE table_schema = 'public'";

      try (Statement statement = connection.createStatement();
          ResultSet result = statement.executeQuery(sql)) {

        java.util.Set<String> tables = new java.util.HashSet<>();

        while (result.next()) {
          tables.add(result.getString("table_name"));
        }

        assertAll(
            () -> assertTrue(tables.contains("users")),
            () -> assertTrue(tables.contains("roles")),
            () -> assertTrue(tables.contains("user_info")),
            () -> assertTrue(tables.contains("edit_history")));
      }
    }
  }

  @Test
  void migrationCreatesForeignKeys() throws Exception {
    String sql =
        "SELECT constraint_name"
            + " FROM information_schema.table_constraints"
            + " WHERE constraint_schema = 'public'"
            + " AND constraint_type = 'FOREIGN KEY'"
            + " AND constraint_name = ?";

    assertAll(
        () -> assertTrue(exists(sql, "fk_user_info_user")),
        () -> assertTrue(exists(sql, "fk_user_info_role")),
        () -> assertTrue(exists(sql, "fk_edit_history_actor")),
        () -> assertTrue(exists(sql, "fk_edit_history_target")));
  }

  @Test
  void migrationCreatesIndexes() throws Exception {
    String sql = 
        "SELECT indexname "
            + "FROM pg_indexes "
            + "WHERE schemaname = 'public' "
            + "AND indexname = ?";

    assertAll(
        () -> assertTrue(exists(sql, "idx_edit_history_actor_time")),
        () -> assertTrue(exists(sql, "idx_edit_history_target_time")));
  }

  @AfterAll 
  static void closeContainer() {
    postgresContainer.stop();
    postgresContainer.close(); 
  }
}

