package ktpm.seminar.n8;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.dbunit.database.DatabaseConnection;
import org.dbunit.database.IDatabaseConnection;
import org.dbunit.dataset.IDataSet;
import org.dbunit.dataset.xml.FlatXmlDataSetBuilder;
import org.dbunit.operation.DatabaseOperation;

@Testcontainers
class ConnectionTest {
    @Container
    static final PostgreSQLContainer<?> postgresContainer = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass");

    @BeforeAll
    static void migrateDatabase() {
        Flyway flyway = Flyway.configure()
                .dataSource(postgresContainer.getJdbcUrl(), postgresContainer.getUsername(),
                        postgresContainer.getPassword())
                .load();
        flyway.migrate();
    }

    static void seedDatabase() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(
                postgresContainer.getJdbcUrl(),
                postgresContainer.getUsername(),
                postgresContainer.getPassword())) {

            IDatabaseConnection dbUnitConnection = new DatabaseConnection(connection);

            IDataSet dataSet = new FlatXmlDataSetBuilder()
                    .build(
                            ConnectionTest.class
                                    .getResourceAsStream(
                                            "/dbunit/users.xml"));

            DatabaseOperation.CLEAN_INSERT.execute(
                    dbUnitConnection,
                    dataSet);
        }
    }

    static void printTable(
            java.sql.Connection connection,
            String tableName) throws Exception {

        String sql = "SELECT * FROM " + tableName;

        try (var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {

            var metadata = result.getMetaData();
            int columnCount = metadata.getColumnCount();

            // Header
            for (int i = 1; i <= columnCount; i++) {
                System.out.printf("%-20s", metadata.getColumnName(i));
            }
            System.out.println();

            // Rows
            while (result.next()) {
                for (int i = 1; i <= columnCount; i++) {
                    System.out.printf(
                            "%-20s",
                            result.getString(i));
                }
                System.out.println();
            }
        }

    }

    static void printAllTables(java.sql.Connection connection) throws Exception {
        String sql = """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_type = 'BASE TABLE'
                ORDER BY table_name
                """;

        try (var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {

            while (result.next()) {
                String tableName = result.getString("table_name");

                System.out.println();
                System.out.println(
                        "========================================================================================================================");
                System.out.println("TABLE: " + tableName);
                System.out.println(
                        "========================================================================================================================");

                printTable(connection, tableName);
            }
        }
    }

    @Test
    void databaseWorks() {
        System.out.println();
        System.out.println("[Database Info]");
        System.out.println("URL: " + postgresContainer.getJdbcUrl());

        System.out.println("Database: " + postgresContainer.getDatabaseName());
        System.out.println("Username: " + postgresContainer.getUsername());
        System.out.println("Password: " + postgresContainer.getPassword());
        System.out.println();
    }

    @Test
    void inspectDatabase() throws Exception {
        System.out.println("[Inspecting Database]");
        try (var connection = java.sql.DriverManager.getConnection(
                postgresContainer.getJdbcUrl(),
                postgresContainer.getUsername(),
                postgresContainer.getPassword())) {

            // printTable(connection, "users");
            printAllTables(connection);
        }
    }

}