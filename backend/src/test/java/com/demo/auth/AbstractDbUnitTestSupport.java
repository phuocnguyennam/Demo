package com.demo.auth;

import java.io.InputStream;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Locale;
import java.util.Map;

import javax.sql.DataSource;

import org.dbunit.Assertion;
import org.dbunit.database.DatabaseConfig;
import org.dbunit.database.DatabaseDataSourceConnection;
import org.dbunit.database.IDatabaseConnection;
import org.dbunit.dataset.IDataSet;
import org.dbunit.dataset.ITable;
import org.dbunit.dataset.ReplacementDataSet;
import org.dbunit.dataset.SortedTable;
import org.dbunit.dataset.datatype.AbstractDataType;
import org.dbunit.dataset.datatype.DataType;
import org.dbunit.dataset.datatype.DataTypeException;
import org.dbunit.dataset.datatype.TypeCastException;
import org.dbunit.dataset.filter.DefaultColumnFilter;
import org.dbunit.dataset.xml.FlatXmlDataSetBuilder;
import org.dbunit.ext.postgresql.PostgresqlDataTypeFactory;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import net.datafaker.Faker;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractDbUnitTestSupport {
    protected static final String ADMIN_EMAIL = "admin@example.com";
    protected static final String ADMIN_PASSWORD = "Admin@123456";

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("dbunit_demo_db")
                    .withUsername("test")
                    .withPassword("test");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("DATABASE_URL", POSTGRES::getJdbcUrl);
        registry.add("DB_USER", POSTGRES::getUsername);
        registry.add("DB_PASSWORD", POSTGRES::getPassword);
        registry.add("JWT_SECRET", () -> "dbunit-demo-jwt-secret-key");
        registry.add("ADMIN_EMAIL", () -> ADMIN_EMAIL);
        registry.add("ADMIN_PASSWORD", () -> ADMIN_PASSWORD);
    }

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected DataSource dataSource;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @LocalServerPort
    protected int port;

    // Setup Faker ở mức cơ bản nhất phục vụ sinh dữ liệu đầu vào cho API
    protected final Faker faker = new Faker(Locale.ENGLISH);
    protected final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(10);

    protected IDatabaseConnection dbUnitConnection;

    @BeforeEach
    protected void setUpBaseDbUnit() throws Exception {
        resetDatabaseToInitialSeed();
        dbUnitConnection = createCustomDbUnitConnection();
    }

    @AfterEach
    protected void tearDownBaseDbUnit() throws Exception {
        if (dbUnitConnection != null) {
            dbUnitConnection.close();
        }
    }

    /**
     * Custom DataType cho phép DBUnit vừa đọc (SELECT/Record) vừa ghi (INSERT/REFRESH/Playback)
     * cột kiểu JSON/JSONB của PostgreSQL thông qua Types.OTHER.
     */
    private static final DataType POSTGRES_JSONB_DATA_TYPE =
            new AbstractDataType("jsonb", Types.OTHER, String.class, false) {
                @Override
                public Object typeCast(Object value) throws TypeCastException {
                    return value == null ? null : value.toString();
                }

                @Override
                public Object getSqlValue(int column, ResultSet resultSet) throws SQLException {
                    return resultSet.getString(column);
                }

                @Override
                public void setSqlValue(Object value, int column, PreparedStatement statement)
                        throws SQLException {
                    if (value == null) {
                        statement.setNull(column, Types.OTHER);
                    } else {
                        statement.setObject(column, value.toString(), Types.OTHER);
                    }
                }
            };

    /**
     * Khởi tạo IDatabaseConnection của DBUnit kèm cấu hình PostgresqlDataTypeFactory
     * đã mở rộng để đọc và ghi được cột kiểu JSONB (changed_fields trong bảng edit_history).
     */
    protected IDatabaseConnection createCustomDbUnitConnection() throws Exception {
        IDatabaseConnection connection = new DatabaseDataSourceConnection(dataSource);
        DatabaseConfig config = connection.getConfig();
        config.setProperty(DatabaseConfig.PROPERTY_DATATYPE_FACTORY, new PostgresqlDataTypeFactory() {
            @Override
            public DataType createDataType(int sqlType, String sqlTypeName) throws DataTypeException {
                if (sqlType == Types.OTHER
                        && ("jsonb".equalsIgnoreCase(sqlTypeName) || "json".equalsIgnoreCase(sqlTypeName))) {
                    return POSTGRES_JSONB_DATA_TYPE;
                }
                return super.createDataType(sqlType, sqlTypeName);
            }
        });
        return connection;
    }

    /**
     * Đưa trạng thái Database về đúng trạng thái chuẩn ban đầu (chỉ giữ lại tài khoản Admin mặc định và 2 roles).
     */
    protected void resetDatabaseToInitialSeed() {
        jdbcTemplate.execute("DELETE FROM edit_history");
        jdbcTemplate.execute(
                "DELETE FROM user_info WHERE id <> (SELECT id FROM users WHERE email = 'admin@example.com')");
        jdbcTemplate.execute("DELETE FROM users WHERE email <> 'admin@example.com'");
        jdbcTemplate.update(
                "UPDATE user_info SET name = 'Admin', phone_number = NULL, "
                        + "user_role = (SELECT role_id FROM roles WHERE role_name = 'admin') "
                        + "WHERE id = (SELECT id FROM users WHERE email = 'admin@example.com')");
    }

    protected String generateValidPassword() {
        return faker.letterify("????") + faker.numerify("####") + "Aa1!";
    }

    protected Integer roleId(String roleName) {
        return jdbcTemplate.queryForObject(
                "SELECT role_id FROM roles WHERE role_name = ?", Integer.class, roleName);
    }

    protected Long adminUserId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, ADMIN_EMAIL);
    }

    protected ReplacementDataSet loadReplacementDataSet(String resourcePath, Map<String, String> replacements)
            throws Exception {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            assertNotNull(is, "Không tìm thấy dataset resource: " + resourcePath);
            IDataSet flatXml = new FlatXmlDataSetBuilder().setColumnSensing(true).build(is);
            ReplacementDataSet replacementDataSet = new ReplacementDataSet(flatXml);
            replacementDataSet.addReplacementObject("[NULL]", null);
            replacements.forEach(replacementDataSet::addReplacementSubstring);
            return replacementDataSet;
        }
    }

    protected static void assertTableEquals(
            ITable expectedTable,
            ITable actualTable,
            String[] sortColumns,
            String[] excludedColumns) throws Exception {
        SortedTable sortedActual = new SortedTable(actualTable, sortColumns);
        sortedActual.setUseComparable(true);
        ITable filteredActual = DefaultColumnFilter.excludedColumnsTable(sortedActual, excludedColumns);
        ITable filteredExpected = DefaultColumnFilter.excludedColumnsTable(expectedTable, excludedColumns);
        Assertion.assertEquals(filteredExpected, filteredActual);
    }

    protected ResponseEntity<Map<String, Object>> request(
            String path, HttpMethod method, Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(
                "http://localhost:" + port + path,
                method,
                new HttpEntity<>(body, headers),
                new ParameterizedTypeReference<>() {});
    }
}
