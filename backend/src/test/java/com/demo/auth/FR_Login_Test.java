package com.demo.auth;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import net.datafaker.Faker;
import org.dbunit.Assertion;
import org.dbunit.dataset.Column;
import org.dbunit.dataset.IDataSet;
import org.dbunit.dataset.ITable;
import org.dbunit.dataset.ReplacementDataSet;
import org.dbunit.dataset.filter.DefaultColumnFilter;
import org.dbunit.dataset.xml.FlatXmlDataSetBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FR_Login_Test extends AbstractDbUnitTestSupport {

    private static final Logger log = LoggerFactory.getLogger(FR_Login_Test.class);
    private static final Path REPORT_FILE = Path.of("target", "faker-dbunit-login-report.log");
    private static final Faker STATIC_FAKER = new Faker(Locale.ENGLISH);
    private static final String USER_EMAIL = "[USER_EMAIL]";
    private static final String USER_PASSWORD = "[USER_PASSWORD]";

    private String loginEmail;
    private String loginPassword;
    private String loginName;
    private Long loginUserId;

    @BeforeAll
    static void initReportFile() throws Exception {
        Files.createDirectories(REPORT_FILE.getParent());
        Files.writeString(
                REPORT_FILE,
                "BÁO CÁO CHI TIẾT DỮ LIỆU FAKER & DBUNIT (FR-02 ĐĂNG NHẬP: TC01 -> TC20)\n",
                StandardCharsets.UTF_8);
    }

    static Stream<Arguments> validLoginCases() {
        return Stream.of(
                Arguments.of("TC01 - EP Valid (Mật khẩu hợp lệ)", false, 12),
                Arguments.of("TC02 - BVA Valid (Mật khẩu Min = 8 ký tự)", false, 8),
                Arguments.of("TC03 - BVA Valid (Mật khẩu Max = 32 ký tự)", false, 32),
                Arguments.of("TC04 - EP Valid (Email có khoảng trắng và chữ HOA)", true, 12));
    }

    @Order(1)
    @ParameterizedTest(name = "{0}")
    @MethodSource("validLoginCases")
    @DisplayName("TC01 -> TC04 (EP & BVA Hợp lệ): POST /api/login -> 200 OK -> DbUnit")
    void shouldLoginWithValidCredentials(
            String testCaseName, boolean normalizeEmail, int passwordLength) throws Exception {
        seedLoginUser(passwordOfLength(passwordLength));
        String requestEmail = normalizeEmail
                ? "  " + loginEmail.toUpperCase(Locale.ROOT) + "  "
                : loginEmail;
        Map<String, Object> payload = orderedPayload("email", requestEmail, "password", loginPassword);
        ResponseEntity<Map<String, Object>> response = request("/api/login", HttpMethod.POST, payload, null);

        assertEquals(200, response.getStatusCode().value(), testCaseName);
        assertEquals("Đăng nhập thành công!", response.getBody().get("message"));
        assertNotNull(response.getBody().get("token"));
        assertUserResponse(response.getBody(), loginEmail);
        assertDatabaseUnchanged(testCaseName, payload, response, "/datasets/expected-login-success.xml");
    }

    @Order(2)
    @Test
    @DisplayName("TC05 - EP Valid (GET /api/me với JWT hợp lệ)")
    void shouldRestoreSessionWithValidToken() throws Exception {
        seedLoginUser(passwordOfLength(12));
        ResponseEntity<Map<String, Object>> loginResponse = request(
                "/api/login",
                HttpMethod.POST,
                orderedPayload("email", loginEmail, "password", loginPassword),
                null);
        assertEquals(200, loginResponse.getStatusCode().value());
        String token = (String) loginResponse.getBody().get("token");
        assertNotNull(token);

        Map<String, Object> requestDetails = orderedPayload("endpoint", "GET /api/me");
        ResponseEntity<Map<String, Object>> response = request("/api/me", HttpMethod.GET, null, token);
        assertEquals(200, response.getStatusCode().value());
        assertUserResponse(response.getBody(), loginEmail);
        assertDatabaseUnchanged(
                "TC05 - EP Valid (GET /api/me với JWT hợp lệ)",
                requestDetails,
                response,
                "/datasets/expected-login-success.xml");
    }

    static Stream<Arguments> invalidLoginInputCases() {
        return Stream.of(
                Arguments.of(
                        "TC06 - EP Invalid (Email rỗng)",
                        orderedPayload("email", " ", "password", USER_PASSWORD),
                        "Vui lòng nhập cả email và mật khẩu."),
                Arguments.of(
                        "TC07 - EP Invalid (Thiếu email)",
                        orderedPayload("password", USER_PASSWORD),
                        "Vui lòng nhập cả email và mật khẩu."),
                Arguments.of(
                        "TC08 - EP Invalid (Email không phải chuỗi)",
                        orderedPayload("email", STATIC_FAKER.number().randomNumber(), "password", USER_PASSWORD),
                        "Vui lòng nhập cả email và mật khẩu."),
                Arguments.of(
                        "TC09 - EP Invalid (Email sai định dạng)",
                        orderedPayload("email", STATIC_FAKER.lorem().word() + "@invalid",
                                "password", USER_PASSWORD),
                        "Định dạng email không hợp lệ."),
                Arguments.of(
                        "TC10 - EP Invalid (Mật khẩu rỗng)",
                        orderedPayload("email", USER_EMAIL, "password", ""),
                        "Vui lòng nhập cả email và mật khẩu."),
                Arguments.of(
                        "TC11 - EP Invalid (Thiếu mật khẩu)",
                        orderedPayload("email", USER_EMAIL),
                        "Vui lòng nhập cả email và mật khẩu."),
                Arguments.of(
                        "TC12 - EP Invalid (Mật khẩu không phải chuỗi)",
                        orderedPayload("email", USER_EMAIL, "password", STATIC_FAKER.number().randomNumber()),
                        "Vui lòng nhập cả email và mật khẩu."),
                Arguments.of(
                        "TC13 - EP Invalid (Body không phải object)",
                        List.of(STATIC_FAKER.lorem().word()),
                        "Vui lòng nhập cả email và mật khẩu."));
    }

    @Order(3)
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidLoginInputCases")
    @DisplayName("TC06 -> TC13 (EP Invalid): POST /api/login -> 400 Bad Request -> DbUnit")
    void shouldRejectInvalidLoginInputs(
            String testCaseName, Object payloadTemplate, String expectedError) throws Exception {
        seedLoginUser(passwordOfLength(12));
        Object payload = resolvePayload(payloadTemplate);
        ResponseEntity<Map<String, Object>> response = request("/api/login", HttpMethod.POST, payload, null);

        assertEquals(400, response.getStatusCode().value(), testCaseName);
        assertEquals(expectedError, response.getBody().get("error"));
        assertDatabaseUnchanged(testCaseName, payload, response, "/datasets/expected-login-unchanged.xml");
    }

    static Stream<Arguments> authenticationFailureCases() {
        return Stream.of(
                Arguments.of("TC14 - EP Invalid (Email chưa tồn tại)", "unknown", passwordOfLength(12)),
                Arguments.of("TC15 - EP Invalid (Mật khẩu sai)", "known", passwordOfLength(12)),
                Arguments.of("TC16 - BVA Invalid (Mật khẩu sai dài 7 ký tự)", "known", passwordOfLength(7)),
                Arguments.of("TC17 - BVA Invalid (Mật khẩu sai dài 33 ký tự)", "known", passwordOfLength(33)));
    }

    @Order(4)
    @ParameterizedTest(name = "{0}")
    @MethodSource("authenticationFailureCases")
    @DisplayName("TC14 -> TC17 (EP & BVA Không xác thực): POST /api/login -> 401 Unauthorized -> DbUnit")
    void shouldRejectUnknownUserOrIncorrectPassword(
            String testCaseName, String emailMode, String password) throws Exception {
        seedLoginUser(passwordOfLength(12));
        String email = "unknown".equals(emailMode) ? STATIC_FAKER.internet().emailAddress() : loginEmail;
        String requestPassword = password.equals(loginPassword) ? differentPassword(loginPassword) : password;
        Map<String, Object> payload = orderedPayload("email", email, "password", requestPassword);
        ResponseEntity<Map<String, Object>> response = request("/api/login", HttpMethod.POST, payload, null);

        assertEquals(401, response.getStatusCode().value(), testCaseName);
        assertEquals("Email hoặc mật khẩu không chính xác.", response.getBody().get("error"));
        assertDatabaseUnchanged(testCaseName, payload, response, "/datasets/expected-login-unchanged.xml");
    }

    static Stream<Arguments> invalidSessionTokenCases() {
        return Stream.of(
                Arguments.of("TC18 - EP Invalid (Thiếu token)", "missing"),
                Arguments.of("TC19 - EP Invalid (Token rỗng)", "blank"),
                Arguments.of("TC20 - EP Invalid (Token sai định dạng)", "invalid"));
    }

    @Order(5)
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidSessionTokenCases")
    @DisplayName("TC18 -> TC20 (EP Invalid): GET /api/me -> 401/403 -> DbUnit")
    void shouldRejectMissingBlankOrInvalidSessionToken(String testCaseName, String tokenMode) throws Exception {
        seedLoginUser(passwordOfLength(12));
        String token = switch (tokenMode) {
            case "missing" -> null;
            case "blank" -> "";
            default -> STATIC_FAKER.regexify("[A-Za-z0-9_-]{24}");
        };
        Map<String, Object> requestDetails = orderedPayload(
                "endpoint", "GET /api/me", "authorization", tokenMode);
        ResponseEntity<Map<String, Object>> response = request("/api/me", HttpMethod.GET, null, token);

        if ("invalid".equals(tokenMode)) {
            assertEquals(403, response.getStatusCode().value(), testCaseName);
            assertEquals("Token không hợp lệ hoặc đã hết hạn.", response.getBody().get("error"));
        } else {
            assertEquals(401, response.getStatusCode().value(), testCaseName);
            assertEquals("Không tìm thấy token xác thực.", response.getBody().get("error"));
        }
        assertDatabaseUnchanged(testCaseName, requestDetails, response, "/datasets/expected-login-unchanged.xml");
    }

    private void seedLoginUser(String password) {
        loginEmail = STATIC_FAKER.internet().emailAddress();
        loginPassword = password;
        loginName = STATIC_FAKER.name().fullName();
        loginUserId = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash) VALUES (?, ?) RETURNING id",
                Long.class,
                loginEmail,
                passwordEncoder.encode(loginPassword));
        jdbcTemplate.update(
                "INSERT INTO user_info (id, name, user_role) VALUES (?, ?, ?)",
                loginUserId,
                loginName,
                roleId("user"));
    }

    private void assertDatabaseUnchanged(
            String testCaseName,
            Object requestPayload,
            ResponseEntity<Map<String, Object>> response,
            String datasetPath) throws Exception {
        ReplacementDataSet expectedDataSet = loadExpectedDataSet(datasetPath);
        expectedDataSet.addReplacementObject("[ADMIN_EMAIL]", ADMIN_EMAIL);
        expectedDataSet.addReplacementObject("[ADMIN_NAME]", "Admin");
        expectedDataSet.addReplacementObject("[ADMIN_ROLE_ID]", roleId("admin"));
        expectedDataSet.addReplacementObject("[EXPECTED_ID]", loginUserId);
        expectedDataSet.addReplacementObject("[EXPECTED_EMAIL]", loginEmail);
        expectedDataSet.addReplacementObject("[EXPECTED_NAME]", loginName);
        expectedDataSet.addReplacementObject("[EXPECTED_PHONE]", null);
        expectedDataSet.addReplacementObject("[EXPECTED_ROLE_ID]", roleId("user"));
        expectedDataSet.addReplacementObject("[NULL]", null);

        IDataSet actualDataSet = dbUnitConnection.createDataSet();
        ITable actualUsers = actualDataSet.getTable("users");
        ITable actualUserInfo = actualDataSet.getTable("user_info");
        ITable actualEditHistory = actualDataSet.getTable("edit_history");
        logTestSnapshot(testCaseName, requestPayload, response, expectedDataSet,
                actualUsers, actualUserInfo, actualEditHistory);

        ITable filteredActualUsers = DefaultColumnFilter.excludedColumnsTable(
                actualUsers, new String[]{"password_hash", "created_at"});
        Assertion.assertEquals(expectedDataSet.getTable("users"), filteredActualUsers);
        Assertion.assertEquals(expectedDataSet.getTable("user_info"), actualUserInfo);
        assertEquals(0, actualEditHistory.getRowCount(), "Đăng nhập không được tạo edit_history.");
    }

    private ReplacementDataSet loadExpectedDataSet(String resourcePath) throws Exception {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            assertNotNull(is, "Không tìm thấy dataset resource: " + resourcePath);
            IDataSet flatXml = new FlatXmlDataSetBuilder().setColumnSensing(true).build(is);
            return new ReplacementDataSet(flatXml);
        }
    }

    private void logTestSnapshot(
            String testCaseName,
            Object requestPayload,
            ResponseEntity<Map<String, Object>> response,
            IDataSet expectedDataSet,
            ITable actualUsers,
            ITable actualUserInfo,
            ITable actualEditHistory) throws Exception {
        StringBuilder sb = new StringBuilder("\n");
        sb.append("================================================================================\n");
        sb.append(" KỊCH BẢN : ").append(testCaseName).append("\n");
        sb.append("--------------------------------------------------------------------------------\n");
        sb.append(" [1] DỮ LIỆU FAKER TẠO RA (REQUEST PAYLOAD / PARAMS):\n");
        if (requestPayload instanceof Map<?, ?> payload) {
            for (Map.Entry<?, ?> entry : payload.entrySet()) {
                Object value = entry.getValue();
                String length = value instanceof String text ? " (len=" + text.length() + ")" : "";
                sb.append(String.format("     - %-16s : %s%s%n",
                        entry.getKey(), formatValue(value), length));
            }
        } else {
            sb.append("     - body             : ").append(formatValue(requestPayload)).append("\n");
        }

        sb.append(" [2] KẾT QUẢ PHẢN HỒI TỪ BACKEND (HTTP RESPONSE):\n");
        sb.append("     - Status Code      : ").append(response.getStatusCode().value()).append("\n");
        sb.append("     - Response Body    : ").append(formatResponseBody(response.getBody())).append("\n");
        sb.append("--------------------------------------------------------------------------------\n");
        sb.append(" [3] BẢNG MONG ĐỢI ĐỘNG DBUNIT (EXPECTED DATASET):\n");
        sb.append(formatITable("expected.users", expectedDataSet.getTable("users")));
        sb.append(formatITable("expected.user_info", expectedDataSet.getTable("user_info")));
        sb.append("   * Bảng expected.edit_history (0 dòng)\n");
        sb.append("--------------------------------------------------------------------------------\n");
        sb.append(" [4] BẢNG THỰC TẾ TRONG POSTGRESQL (ACTUAL DATABASE):\n");
        sb.append(formatITable("actual.users", actualUsers));
        sb.append(formatITable("actual.user_info", actualUserInfo));
        sb.append(formatITable("actual.edit_history", actualEditHistory));
        sb.append("================================================================================");

        log.info(sb.toString());
        Files.writeString(
                REPORT_FILE,
                sb.append("\n").toString(),
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    private static String formatValue(Object value) {
        return value == null ? "null" : String.valueOf(value);
    }

    private static String formatResponseBody(Map<String, Object> body) {
        if (body == null) {
            return "null";
        }
        Map<String, Object> safeBody = new LinkedHashMap<>(body);
        if (safeBody.containsKey("token")) {
            safeBody.put("token", "[JWT]");
        }
        return safeBody.toString();
    }

    private static String passwordOfLength(int length) {
        return switch (length) {
            case 7 -> STATIC_FAKER.regexify("[A-Z][a-z]{4}[0-9][!@#$]");
            case 8 -> STATIC_FAKER.regexify("[A-Z][a-z]{5}[0-9][!@#$]");
            case 12 -> STATIC_FAKER.regexify("[A-Z][a-z]{9}[0-9][!@#$]");
            case 32 -> STATIC_FAKER.regexify("[A-Z][a-z]{29}[0-9][!@#$]");
            case 33 -> STATIC_FAKER.regexify("[A-Z][a-z]{30}[0-9][!@#$]");
            default -> throw new IllegalArgumentException("Độ dài mật khẩu không được hỗ trợ: " + length);
        };
    }

    private static String differentPassword(String password) {
        return (password.charAt(0) == 'X' ? "Y" : "X") + password.substring(1);
    }

    private static Object resolvePayload(Object payloadTemplate) {
        if (!(payloadTemplate instanceof Map<?, ?> template)) {
            return payloadTemplate;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        template.forEach((key, value) -> payload.put(
                String.valueOf(key),
                USER_EMAIL.equals(value) ? STATIC_FAKER.internet().emailAddress()
                        : USER_PASSWORD.equals(value) ? passwordOfLength(12) : value));
        return payload;
    }

    private void assertUserResponse(Map<String, Object> responseBody, String expectedEmail) {
        Map<?, ?> user = (Map<?, ?>) responseBody.get("user");
        assertNotNull(user);
        assertEquals(loginUserId.longValue(), ((Number) user.get("id")).longValue());
        assertEquals(expectedEmail, user.get("email"));
        assertNotNull(user.get("createdAt"));
    }

    private static Map<String, Object> orderedPayload(Object... values) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            payload.put(String.valueOf(values[i]), values[i + 1]);
        }
        return payload;
    }

    private static String formatITable(String tableTitle, ITable table) throws Exception {
        Column[] columns = table.getTableMetaData().getColumns();
        int rowCount = table.getRowCount();
        int[] widths = new int[columns.length];
        List<String[]> rows = new ArrayList<>(rowCount);

        for (int column = 0; column < columns.length; column++) {
            widths[column] = columns[column].getColumnName().length();
        }
        for (int row = 0; row < rowCount; row++) {
            String[] values = new String[columns.length];
            for (int column = 0; column < columns.length; column++) {
                Object value = table.getValue(row, columns[column].getColumnName());
                String text = value == null ? "NULL" : String.valueOf(value);
                if ("password_hash".equals(columns[column].getColumnName()) && text.length() > 25) {
                    text = text.substring(0, 22) + "...";
                }
                values[column] = text;
                widths[column] = Math.max(widths[column], text.length());
            }
            rows.add(values);
        }

        StringBuilder border = new StringBuilder("     +");
        for (int width : widths) {
            border.append("-".repeat(width + 2)).append("+");
        }
        border.append("\n");
        StringBuilder result = new StringBuilder();
        result.append("   * Bảng ").append(tableTitle).append(" (").append(rowCount).append(" dòng):\n");
        result.append(border).append("     |");
        for (int column = 0; column < columns.length; column++) {
            result.append(String.format(" %-" + widths[column] + "s |", columns[column].getColumnName()));
        }
        result.append("\n").append(border);
        for (String[] row : rows) {
            result.append("     |");
            for (int column = 0; column < row.length; column++) {
                result.append(String.format(" %-" + widths[column] + "s |", row[column]));
            }
            result.append("\n");
        }
        result.append(border);
        return result.toString();
    }
}
