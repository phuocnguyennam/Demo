package com.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.dbunit.Assertion;
import org.dbunit.database.DatabaseConfig;
import org.dbunit.database.DatabaseConnection;
import org.dbunit.dataset.IDataSet;
import org.dbunit.dataset.xml.FlatXmlDataSetBuilder;
import org.dbunit.ext.postgresql.PostgresqlDataTypeFactory;
import org.dbunit.operation.DatabaseOperation;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class T2DBUnitTest {

  @Container
  static final PostgreSQLContainer<?> postgresContainer = new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName("testdb")
      .withUsername("testuser")
      .withPassword("testpass");

  private static MigrateResult migrateResult;

  @BeforeAll
  static void setupDatabase() throws Exception {

    // 1. Create the database schema first
    Flyway.configure()
        .dataSource(
            postgresContainer.getJdbcUrl(),
            postgresContainer.getUsername(),
            postgresContainer.getPassword())
        .load()
        .migrate();

    // 2. Load test data after migration completes
    try (Connection connection = DriverManager.getConnection(
        postgresContainer.getJdbcUrl(),
        postgresContainer.getUsername(),
        postgresContainer.getPassword())) {

      DatabaseConnection dbUnitConnection = new DatabaseConnection(connection);

      try (InputStream inputStream = T2DBUnitTest.class.getResourceAsStream("/datasets/T2/users.xml")) {

        if (inputStream == null) {
          throw new IllegalStateException("Cannot find /datasets/T2/users.xml");
        }

        IDataSet dataSet = new FlatXmlDataSetBuilder().build(inputStream);

        DatabaseOperation.CLEAN_INSERT.execute(dbUnitConnection, dataSet);
      }
    }
  }

  private Connection openConnection() throws SQLException {
    return DriverManager.getConnection(
        postgresContainer.getJdbcUrl(),
        postgresContainer.getUsername(),
        postgresContainer.getPassword());
  }

  private IDataSet loadDataset(String filename) throws Exception {
    try (InputStream input = getClass().getResourceAsStream("/datasets/" + filename)) {
      if (input == null) {
        throw new IllegalStateException("Dataset not found: " + filename);
      }
      return new FlatXmlDataSetBuilder().build(input);
    }
  }

  private void assertTables(String filename, String... tables) throws Exception {

    try (Connection connection = openConnection()) {
      DatabaseConnection dbUnit = new DatabaseConnection(connection);

      dbUnit
          .getConfig()
          .setProperty(DatabaseConfig.PROPERTY_DATATYPE_FACTORY, new PostgresqlDataTypeFactory());

      IDataSet expected = loadDataset(filename);
      IDataSet actual = dbUnit.createDataSet(tables);

      for (String table : tables) {
        Assertion.assertEquals(expected.getTable(table), actual.getTable(table));
      }
    } 
  }

  @Test
  // @DisplayName("Verify that the number of inserted users matches the expected count")
  void verifyInsertionCount() throws Exception {

    try (Connection jdbcConnection = openConnection();
        Statement statement = jdbcConnection.createStatement();
        ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM users")) {

      resultSet.next();

      int count = resultSet.getInt(1);
      assertEquals(3, count, "Expected 3 users in the database");
    }
  }

  @Test
  void compareAgainstExpectedDataset() throws Exception {
    assertTables("T2/expected_users.xml", "users");
    // try (Connection jdbcConnection = openConnection()) {
    //   DatabaseConnection dbUnitConnection = new DatabaseConnection(jdbcConnection);
    //
    //   // Load the expected dataset from the XML file
    //   InputStream inputStream = T2DBUnitTest.class.getResourceAsStream("/datasets/T2/expected_users.xml");
    //
    //   if (inputStream == null) {
    //     throw new IllegalStateException("Cannot find expected_users.xml in src/test/resources/datasets/T2/");
    //   }
    //
    //   try (InputStream datasetStream = inputStream) {
    //     IDataSet expectedDataSet = new FlatXmlDataSetBuilder().build(datasetStream);
    //
    //     // Compare the actual database state with the expected dataset
    //     IDataSet actualDataSet = dbUnitConnection.createDataSet(new String[]{"users"});
    //     org.dbunit.Assertion.assertEquals(expectedDataSet, actualDataSet);
    //   }
    // }
  }
}
