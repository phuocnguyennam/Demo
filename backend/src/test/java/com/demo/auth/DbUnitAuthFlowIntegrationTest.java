package com.demo.auth;

import java.io.InputStream;
import java.sql.Types;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;
import net.datafaker.Faker;
import org.dbunit.Assertion;
import org.dbunit.database.DatabaseConfig;
import org.dbunit.database.DatabaseDataSourceConnection;
import org.dbunit.database.IDatabaseConnection;
import org.dbunit.dataset.IDataSet;
import org.dbunit.dataset.ITable;
import org.dbunit.dataset.ReplacementDataSet;
import org.dbunit.dataset.SortedTable;
import org.dbunit.dataset.datatype.DataType;
import org.dbunit.dataset.datatype.DataTypeException;
import org.dbunit.dataset.filter.DefaultColumnFilter;
import org.dbunit.dataset.xml.FlatXmlDataSetBuilder;
import org.dbunit.ext.postgresql.PostgresqlDataTypeFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kiểm thử tích hợp theo luồng:
 * 1. Sử dụng Faker (mức cơ bản) để tạo dữ liệu tự động.
 * 2. Dùng dữ liệu vừa tạo để gọi REST API.
 * 3. Sử dụng DBUnit để đối chiếu và xác minh kết quả thay đổi bên trong PostgreSQL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class DbUnitAuthFlowIntegrationTest {
    private static final String ADMIN_EMAIL = "admin@example.com";
    private static final String ADMIN_PASSWORD = "Admin@123456";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("dbunit_integration")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("DATABASE_URL", POSTGRES::getJdbcUrl);
        registry.add("DB_USER", POSTGRES::getUsername);
        registry.add("DB_PASSWORD", POSTGRES::getPassword);
        registry.add("JWT_SECRET", () -> "dbunit-integration-test-secret");
        registry.add("ADMIN_EMAIL", () -> ADMIN_EMAIL);
        registry.add("ADMIN_PASSWORD", () -> ADMIN_PASSWORD);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @LocalServerPort
    private int port;

    // Setup Faker ở mức cơ bản nhất để phục vụ việc tích hợp DBUnit
    private final Faker faker = new Faker(Locale.ENGLISH);
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(10);

    private IDatabaseConnection dbUnitConnection;

    @BeforeEach
    void setUpDbUnit() throws Exception {
        // Làm sạch dữ liệu phát sinh ngoài tài khoản admin mặc định trước mỗi test
        jdbcTemplate.execute("DELETE FROM edit_history");
        jdbcTemplate.execute("DELETE FROM user_info WHERE id <> (SELECT id FROM users WHERE email = 'admin@example.com')");
        jdbcTemplate.execute("DELETE FROM users WHERE email <> 'admin@example.com'");

        dbUnitConnection = new DatabaseDataSourceConnection(dataSource);
        DatabaseConfig config = dbUnitConnection.getConfig();
        // Mở rộng PostgresqlDataTypeFactory để hỗ trợ cột kiểu JSONB của bảng edit_history
        config.setProperty(DatabaseConfig.PROPERTY_DATATYPE_FACTORY, new PostgresqlDataTypeFactory() {
            @Override
            public DataType createDataType(int sqlType, String sqlTypeName) throws DataTypeException {
                if (sqlType == Types.OTHER && ("jsonb".equalsIgnoreCase(sqlTypeName) || "json".equalsIgnoreCase(sqlTypeName))) {
                    return DataType.VARCHAR;
                }
                return super.createDataType(sqlType, sqlTypeName);
            }
        });
    }

    @AfterEach
    void tearDownDbUnit() throws Exception {
        if (dbUnitConnection != null) {
            dbUnitConnection.close();
        }
    }

    @Test
    void registerWithFakerDataAndVerifyDatabaseWithDbUnit() throws Exception {
        // 1. Dùng Faker sinh dữ liệu đăng ký tự động
        String rawEmail = faker.internet().emailAddress();
        String normalizedEmail = rawEmail.trim().toLowerCase(Locale.ROOT);
        String defaultName = normalizedEmail.substring(0, normalizedEmail.indexOf('@'));
        String password = generateValidPassword();

        // 2. Gọi API POST /api/register với dữ liệu vừa tạo
        ResponseEntity<Map<String, Object>> response = request(
                "/api/register",
                HttpMethod.POST,
                Map.of(
                        "email", rawEmail,
                        "password", password,
                        "confirmPassword", password
                ),
                null
        );
        assertEquals(201, response.getStatusCode().value());

        // 3. Sử dụng DBUnit để đối chiếu và xác minh kết quả thay đổi trong Database
        ReplacementDataSet expectedDataSet = loadReplacementDataSet(
                "/datasets/expected-register.xml",
                Map.of(
                        "${email}", normalizedEmail,
                        "${default_name}", defaultName,
                        "${user_role_id}", String.valueOf(roleId("user")),
                        "${admin_role_id}", String.valueOf(roleId("admin"))
                )
        );

        IDataSet actualDataSet = dbUnitConnection.createDataSet();

        // Đối chiếu bảng users (bỏ qua các cột tự sinh id, password_hash, created_at)
        assertTableEquals(
                expectedDataSet.getTable("users"),
                actualDataSet.getTable("users"),
                new String[]{"id"},
                new String[]{"id", "password_hash", "created_at"}
        );

        // Xác minh bổ sung mật khẩu lưu trong DB đã được băm khớp với password từ Faker
        ITable usersTable = actualDataSet.getTable("users");
        String storedHash = (String) usersTable.getValue(1, "password_hash");
        assertNotNull(storedHash);
        assertTrue(passwordEncoder.matches(password, storedHash));

        // Đối chiếu bảng user_info (bỏ qua cột tự sinh id)
        assertTableEquals(
                expectedDataSet.getTable("user_info"),
                actualDataSet.getTable("user_info"),
                new String[]{"id"},
                new String[]{"id"}
        );

        // Đảm bảo bảng edit_history vẫn trống khi mới đăng ký
        assertEquals(0, actualDataSet.getTable("edit_history").getRowCount());
    }

    @Test
    void updateProfileWithFakerDataAndVerifyDatabaseWithDbUnit() throws Exception {
        // 1. Dùng Faker sinh dữ liệu tài khoản và dữ liệu cập nhật hồ sơ
        String email = faker.internet().emailAddress().toLowerCase(Locale.ROOT);
        String initialName = email.substring(0, email.indexOf('@'));
        String password = generateValidPassword();
        String updatedName = faker.name().fullName();
        String updatedPhone = faker.numerify("+84 9## ### ###");

        // 2. Gọi chuỗi API: Đăng ký -> Đăng nhập lấy JWT -> Cập nhật hồ sơ (PUT /api/profile)
        ResponseEntity<Map<String, Object>> registerRes = request(
                "/api/register",
                HttpMethod.POST,
                Map.of("email", email, "password", password, "confirmPassword", password),
                null
        );
        assertEquals(201, registerRes.getStatusCode().value());
        long userId = ((Number) ((Map<?, ?>) registerRes.getBody().get("user")).get("id")).longValue();

        ResponseEntity<Map<String, Object>> loginRes = request(
                "/api/login",
                HttpMethod.POST,
                Map.of("email", email, "password", password),
                null
        );
        assertEquals(200, loginRes.getStatusCode().value());
        String token = (String) loginRes.getBody().get("token");
        assertNotNull(token);

        ResponseEntity<Map<String, Object>> updateRes = request(
                "/api/profile",
                HttpMethod.PUT,
                Map.of("name", updatedName, "phoneNumber", updatedPhone),
                token
        );
        assertEquals(200, updateRes.getStatusCode().value());

        // 3. Sử dụng DBUnit đối chiếu bảng user_info và edit_history sau khi cập nhật
        String expectedChangedFieldsJson = String.format(
                "{\"name\": {\"after\": \"%s\", \"before\": \"%s\"}, \"phone_number\": {\"after\": \"%s\", \"before\": null}}",
                updatedName, initialName, updatedPhone
        );

        ReplacementDataSet expectedDataSet = loadReplacementDataSet(
                "/datasets/expected-profile-update.xml",
                Map.of(
                        "${email}", email,
                        "${updated_name}", updatedName,
                        "${updated_phone}", updatedPhone,
                        "${user_role_id}", String.valueOf(roleId("user")),
                        "${admin_role_id}", String.valueOf(roleId("admin")),
                        "${user_id}", String.valueOf(userId),
                        "${old_name}", initialName,
                        "${changed_fields}", expectedChangedFieldsJson
                )
        );

        IDataSet actualDataSet = dbUnitConnection.createDataSet();

        // Đối chiếu bảng user_info đã được cập nhật tên và số điện thoại mới từ Faker
        assertTableEquals(
                expectedDataSet.getTable("user_info"),
                actualDataSet.getTable("user_info"),
                new String[]{"id"},
                new String[]{"id"}
        );

        // Đối chiếu bảng edit_history đã ghi nhận đúng bản ghi thay đổi (bỏ qua history_id, edited_at)
        assertTableEquals(
                expectedDataSet.getTable("edit_history"),
                actualDataSet.getTable("edit_history"),
                new String[]{"history_id"},
                new String[]{"history_id", "edited_at"}
        );
    }

    /**
     * Sinh mật khẩu ngẫu nhiên cơ bản bằng Faker nhưng luôn đảm bảo thỏa mãn quy tắc:
     * 8-32 ký tự, có ít nhất 1 chữ hoa, 1 chữ số và 1 ký tự đặc biệt.
     */
    private String generateValidPassword() {
        return faker.letterify("????") + faker.numerify("####") + "Aa1!";
    }

    private Integer roleId(String roleName) {
        return jdbcTemplate.queryForObject(
                "SELECT role_id FROM roles WHERE role_name = ?", Integer.class, roleName);
    }

    private ReplacementDataSet loadReplacementDataSet(String resourcePath, Map<String, String> replacements)
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

    private static void assertTableEquals(
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

    private ResponseEntity<Map<String, Object>> request(
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

