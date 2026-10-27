package com.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import net.datafaker.Faker;
import org.dbunit.Assertion;
import org.dbunit.database.DatabaseConfig;
import org.dbunit.database.DatabaseConnection;
import org.dbunit.dataset.Column;
import org.dbunit.dataset.DefaultTable;
import org.dbunit.dataset.IDataSet;
import org.dbunit.dataset.ITable;
import org.dbunit.dataset.datatype.DataType;
import org.dbunit.dataset.filter.DefaultColumnFilter;
import org.dbunit.ext.postgresql.PostgresqlDataTypeFactory;
import org.dbunit.operation.DatabaseOperation;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Datafaker supplies varied inputs; an in-memory DBUnit dataset records the expected rows. */
@Testcontainers
class T5DatafakerTest {

  private static final long SEED = 20261027L;
  private static final int FIRST_USER_ID = 101;
  private static final int ADMIN_ROLE_ID = 10;
  private static final int MEMBER_ROLE_ID = 20;

  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>("postgres:16-alpine")
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

  @BeforeEach
  void resetDatabase() throws Exception {
    try (Connection connection = openConnection()) {
      DatabaseOperation.CLEAN_INSERT.execute(
          new DatabaseConnection(connection), loadEmptyDataset());
    }
  }

  private Connection openConnection() throws SQLException {
    return DriverManager.getConnection(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }

  private IDataSet loadEmptyDataset() throws Exception {
    try (var input = getClass().getResourceAsStream("/datasets/empty.xml")) {
      if (input == null) {
        throw new IllegalStateException("Dataset not found: /datasets/empty.xml");
      }
      return new org.dbunit.dataset.xml.FlatXmlDataSetBuilder().build(input);
    }
  }

  // This record is used to hold the generated data for each user, so that we can
  // compare it to the expected dataset.
  private record UserRow(
      int id,
      String email,
      String passwordHash,
      Timestamp createdAt,
      String name,
      String phoneNumber,
      int roleId) {}

  private List<UserRow> generateUsers(int count) {
    // Use a fixed seed to make the generated data deterministic for testing.
    Faker faker = new Faker(Locale.ENGLISH, new Random(SEED));

    List<UserRow> users = new ArrayList<>(count);

    for (int i = 0; i < count; i++) {
      // use index to ensure uniqueness
      String email = "user-%02d-%s@example.test".formatted(i, faker.internet().username());
      String phone = faker.number().digits(10);
      Timestamp createdAt = Timestamp.valueOf(LocalDateTime.of(2026, 1, 1, 0, 0));

      users.add(
          new UserRow(
              FIRST_USER_ID + i,
              email,
              "test-hash-" + faker.number().digits(12),
              createdAt,
              faker.name().fullName(),
              phone,
              i % 2 == 0 ? ADMIN_ROLE_ID : MEMBER_ROLE_ID));
    }
    return users;
  }


  private void insertGeneratedRows(List<UserRow> users) throws SQLException {
    try (Connection connection = openConnection()) {
      try (PreparedStatement roles =
          connection.prepareStatement("INSERT INTO roles (role_id, role_name) VALUES (?, ?)")) {
        roles.setInt(1, ADMIN_ROLE_ID);
        roles.setString(2, "Admin");
        roles.addBatch();
        roles.setInt(1, MEMBER_ROLE_ID);
        roles.setString(2, "Member");
        roles.addBatch();
        roles.executeBatch();
      }
      try (PreparedStatement account =
          connection.prepareStatement(
              "INSERT INTO users (id, email, password_hash, created_at) VALUES (?, ?, ?, ?)")) {
        for (UserRow user : users) {
          account.setInt(1, user.id());
          account.setString(2, user.email());
          account.setString(3, user.passwordHash());
          account.setTimestamp(4, user.createdAt());
          account.addBatch();
        }
        account.executeBatch();
      }
      try (PreparedStatement profile =
          connection.prepareStatement(
              "INSERT INTO user_info (id, name, phone_number, user_role) VALUES (?, ?, ?, ?)")) {
        for (UserRow user : users) {
          profile.setInt(1, user.id());
          profile.setString(2, user.name());
          profile.setString(3, user.phoneNumber());
          profile.setInt(4, user.roleId());
          profile.addBatch();
        }
        profile.executeBatch();
      }
    }
  }

  private ITable expectedUsers(List<UserRow> users) throws Exception {
    DefaultTable table =
        new DefaultTable(
            "users",
            new Column[] {
              new Column("id", DataType.INTEGER),
              new Column("email", DataType.VARCHAR),
              new Column("password_hash", DataType.VARCHAR),
              new Column("created_at", DataType.TIMESTAMP)
            });
    for (UserRow user : users) {
      table.addRow(new Object[] {user.id(), user.email(), user.passwordHash(), user.createdAt()});
    }
    return table;
  }

  private ITable expectedRoles() throws Exception {
    DefaultTable table =
        new DefaultTable(
            "roles",
            new Column[] {
              new Column("role_id", DataType.INTEGER), new Column("role_name", DataType.VARCHAR)
            });
    table.addRow(new Object[] {ADMIN_ROLE_ID, "Admin"});
    table.addRow(new Object[] {MEMBER_ROLE_ID, "Member"});
    return table;
  }

  private ITable expectedProfiles(List<UserRow> users) throws Exception {
    DefaultTable table =
        new DefaultTable(
            "user_info",
            new Column[] {
              new Column("id", DataType.INTEGER),
              new Column("name", DataType.VARCHAR),
              new Column("phone_number", DataType.VARCHAR),
              new Column("user_role", DataType.INTEGER)
            });
    for (UserRow user : users) {
      table.addRow(new Object[] {user.id(), user.name(), user.phoneNumber(), user.roleId()});
    }
    return table;
  }

  private void assertGeneratedDataMatches(List<UserRow> users) throws Exception {
    try (Connection connection = openConnection()) {
      DatabaseConnection dbUnit = new DatabaseConnection(connection);
      dbUnit
          .getConfig()
          .setProperty(DatabaseConfig.PROPERTY_DATATYPE_FACTORY, new PostgresqlDataTypeFactory());

      IDataSet actual = dbUnit.createDataSet(new String[] {"users", "roles", "user_info"});

      // Exclude the "created_at" column from the comparison.
      Assertion.assertEquals(
          DefaultColumnFilter.excludedColumnsTable(
              expectedUsers(users), new String[] {"created_at"}),
          DefaultColumnFilter.excludedColumnsTable(
              actual.getTable("users"), new String[] {"created_at"}));
      
      // Compare the roles and user_info tables directly.
      Assertion.assertEquals(expectedRoles(), actual.getTable("roles"));
      Assertion.assertEquals(expectedProfiles(users), actual.getTable("user_info"));
    }
  }

  @Test
  @DisplayName("Generated users and profiles round-trip exactly through the schema")
  void generatedRowsMatchExpectedDataset() throws Exception {
    List<UserRow> users = generateUsers(10);
    insertGeneratedRows(users);
    assertGeneratedDataMatches(users);
  }

  @Test
  @DisplayName("Unique email and profile foreign keys reject deliberate violations")
  void constraintsRejectDeliberateInvalidRows() throws Exception {
    List<UserRow> users = generateUsers(10);
    insertGeneratedRows(users);

    // Attempt to insert a duplicate email 
    try (Connection connection = openConnection()) {
      SQLException duplicateEmail =
          assertThrows(
              SQLException.class,
              () -> {
                try (PreparedStatement statement =
                    connection.prepareStatement(
                        "INSERT INTO users (id, email, password_hash) VALUES (?, ?, ?)")) {
                  statement.setInt(1, FIRST_USER_ID + users.size());
                  statement.setString(2, users.get(0).email());
                  statement.setString(3, "duplicate-test-hash");
                  statement.executeUpdate();
                }
              });
      assertEquals("23505", duplicateEmail.getSQLState(), "expected unique violation");

      // Attempt to insert a profile with a non-existent user ID.
      SQLException missingUser =
          assertThrows(
              SQLException.class,
              () -> {
                try (PreparedStatement statement =
                    connection.prepareStatement(
                        "INSERT INTO user_info (id, name, user_role) VALUES (?, ?, ?)")) {
                  statement.setInt(1, 999_999);
                  statement.setString(2, "Orphan Profile");
                  statement.setInt(3, ADMIN_ROLE_ID);
                  statement.executeUpdate();
                }
              });
      assertEquals("23503", missingUser.getSQLState(), "expected foreign-key violation");
    }
    assertGeneratedDataMatches(users);
  }
}
