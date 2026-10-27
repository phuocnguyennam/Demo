package com.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
class T3CRUDTest {

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

  /**
   * FlatXmlDataSet cannot express SQL NULL: an absent attribute means "ignore
   * this column", which
   * changes the column count and makes the comparison fail. ReplacementDataSet
   * maps the literal
   * token "[null]" to a real null so a dataset can declare a column and set it to
   * NULL.
   */
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

  private void assertTables(String filename, String... tables) throws Exception {

    // Force UTC for the duration of this method so TIMESTAMP WITH TIME ZONE values
    TimeZone defaultTimeZone = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

    try (Connection connection = openConnection()) {
      DatabaseConnection dbUnit = new DatabaseConnection(connection);

      dbUnit
          .getConfig()
          .setProperty(DatabaseConfig.PROPERTY_DATATYPE_FACTORY, new PostgresqlDataTypeFactory());

      IDataSet expected = loadDatasetWithNulls(filename);
      IDataSet actual = dbUnit.createDataSet(tables);

      for (String table : tables) {
        Assertion.assertEquals(expected.getTable(table), actual.getTable(table));
      }
    } finally {
      // Restore the original time zone so other tests are not affected.
      TimeZone.setDefault(defaultTimeZone);
    }
  }

  @Test
  void insertTwoUsersThenDeleteOne() throws Exception {
    try (Connection connection = openConnection();
        var statement = connection.createStatement()) {

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

      assertEquals(1, statement.executeUpdate("DELETE FROM users WHERE id = 102"));
    }

    assertTables("T3/expected_01.xml", "users");
  }

  @Test
  void insertTwoUsersAndProfilesThenDeleteOne() throws Exception {
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

      assertEquals(1, statement.executeUpdate("DELETE FROM user_info WHERE id = 102"));

      assertEquals(1, statement.executeUpdate("DELETE FROM users WHERE id = 102"));
    }

    assertTables("T3/expected_02.xml", "users", "roles", "user_info");
  }

  @Test
  void insertTwoUsersUpdateOneThenDeleteOther() throws Exception {
    try (Connection connection = openConnection();
        var statement = connection.createStatement()) {

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

      assertEquals(
          1,
          statement.executeUpdate(
              """
                  UPDATE users
                  SET email = 'alice.new@example.com'
                  WHERE id = 101
                  """));

      assertEquals(1, statement.executeUpdate("DELETE FROM users WHERE id = 102"));
    }

    assertTables("T3/expected_03.xml", "users");
  }

  @Test
  void cannotDeleteUserReferencedByUserInfo() throws Exception {
    try (Connection connection = openConnection();
        var statement = connection.createStatement()) {

      statement.executeUpdate(
          """
              INSERT INTO roles (role_id, role_name)
              VALUES (10, 'Admin')
              """);

      statement.executeUpdate(
          """
              INSERT INTO users
                  (id, email, password_hash, created_at)
              VALUES
                  (101, 'alice@example.com', 'hash_alice',
                   '2026-01-01 00:00:00+00')
              """);

      statement.executeUpdate(
          """
              INSERT INTO user_info (id, name, user_role)
              VALUES (101, 'Alice', 10)
              """);

      assertThrows(
          SQLException.class, () -> statement.executeUpdate("DELETE FROM users WHERE id = 101"));
    }

    // The failed DELETE must leave the original rows intact.
    // A new connection sees the committed setup statements.
    assertTables("T3/expected_04.xml", "users", "roles", "user_info");
  }
}
