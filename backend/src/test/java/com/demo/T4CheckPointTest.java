package com.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.TimeZone;
import org.dbunit.Assertion;
import org.dbunit.database.DatabaseConfig;
import org.dbunit.database.DatabaseConnection;
import org.dbunit.dataset.IDataSet;
import org.dbunit.dataset.ReplacementDataSet;
import org.dbunit.dataset.xml.FlatXmlDataSetBuilder;
import org.dbunit.ext.postgresql.PostgresqlDataTypeFactory;
import org.dbunit.operation.DatabaseOperation;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class T4CheckPointTest {

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("testdb")
      .withUsername("testuser")
      .withPassword("testpass");

  @BeforeAll
  static void migrate() {
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .load()
        .migrate();
  }

  private Connection openConnection() throws SQLException {
    return DriverManager.getConnection(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }

  private IDataSet loadDataset(String filename) throws Exception {
    try (InputStream input = getClass().getResourceAsStream("/datasets/" + filename)) {
      if (input == null) {
        throw new IllegalStateException("Dataset not found: " + filename);
      }
      return new FlatXmlDataSetBuilder().build(input);
    }
  }

  private IDataSet loadDatasetWithNulls(String filename) throws Exception {
    ReplacementDataSet dataSet = new ReplacementDataSet(loadDataset(filename));
    dataSet.addReplacementObject("[null]", null);
    return dataSet;
  }

  @BeforeEach
  void resetDatabase() throws Exception {
    try (Connection connection = openConnection()) {
      DatabaseConnection dbUnit = new DatabaseConnection(connection);

      DatabaseOperation.CLEAN_INSERT.execute(dbUnit, loadDataset("empty.xml"));
    }
  }

  private void assertTables(String checkpoint, String filename, String... tables) throws Exception {
    TimeZone originalTimeZone = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

    try (Connection connection = openConnection()) {
      DatabaseConnection dbUnit = new DatabaseConnection(connection);

      dbUnit
          .getConfig()
          .setProperty(DatabaseConfig.PROPERTY_DATATYPE_FACTORY, new PostgresqlDataTypeFactory());

      IDataSet expected = loadDatasetWithNulls(filename);
      IDataSet actual = dbUnit.createDataSet(tables);

      for (String table : tables) {
        try {
          Assertion.assertEquals(expected.getTable(table), actual.getTable(table));
        } catch (AssertionError e) {
          throw new AssertionError(
              "Checkpoint '" + checkpoint + "' failed for table '" + table + "': " + e.getMessage(),
              e);
        }
      }
    } finally {
      TimeZone.setDefault(originalTimeZone);
    }
  }

  @Test
  void deleteUserWithIntermediateCheckpoint() throws Exception {

    // Arrange: create roles, users, and their profiles.
    try (Connection connection = openConnection();
        var statement = connection.createStatement()) {

      statement.executeUpdate(
          """
              INSERT INTO roles (role_id, role_name)
              VALUES (10, 'Admin'), (20, 'Member')
              """);

      statement.executeUpdate(
          """
              INSERT INTO users
                  (id, email, password_hash, created_at)
              VALUES
                  (101, 'alice@example.com', 'hash_alice',
                   '2026-01-01 00:00:00+00'),
                  (102, 'bob@example.com', 'hash_bob',
                   '2026-01-01 00:00:00+00')
              """);

      statement.executeUpdate(
          """
              INSERT INTO user_info
                  (id, name, phone_number, user_role)
              VALUES
                  (101, 'Alice', '0123456789', 10),
                  (102, 'Bob', '0987654321', 20)
              """);

      // Checkpoint 1: verify the starting state.
      assertTables("Checkpoint 1 - Initial DB State", "T4/checkpoint_01.xml", "users", "roles", "user_info");

      // Step 1: delete Bob's profile.
      assertEquals(1, statement.executeUpdate("DELETE FROM user_info WHERE id = 102"));

      // // Demo delete Alice profile
      // assertEquals(1, statement.executeUpdate("DELETE FROM user_info WHERE id = 101"));
    }

    // Checkpoint 2: Bob's profile is gone, but his account
    // still exists. Alice's information must remain unchanged.
    assertTables("Checkpoint 2 - After deleting Bob's profile", "T4/checkpoint_02.xml", "users", "roles", "user_info");

    // Step 2: delete Bob's account.
    try (Connection connection = openConnection();
      var statement = connection.createStatement()) {
      assertEquals(1, statement.executeUpdate("DELETE FROM users WHERE id = 102"));

      // // Demo delete Alice profile
      // assertEquals(1, statement.executeUpdate("DELETE FROM user_info WHERE id = 101"));
    }

    // Checkpoint 3: verify the final state.
    assertTables("Checkpoint 3 - After deleting Bob's account", "T4/checkpoint_03.xml", "users", "roles", "user_info");
  }
}
