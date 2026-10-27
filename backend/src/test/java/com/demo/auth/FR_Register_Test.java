package com.demo.auth;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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

/**
 * Kiểm thử tích hợp chức năng Đăng ký (FR-01) áp dụng Equivalence Partitioning (EP) &
 * Boundary Value Analysis (BVA) theo đúng thứ tự TC01 -> TC17.
 * Kế thừa trực tiếp từ {@link AbstractDbUnitTestSupport} để dùng chung kết nối Testcontainers & DbUnit.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FR_Register_Test extends AbstractDbUnitTestSupport {

    private static final Logger log = LoggerFactory.getLogger(FR_Register_Test.class);
    private static final Path REPORT_FILE = Path.of("target", "faker-dbunit-report.log");
    private static final Faker STATIC_FAKER = new Faker(Locale.ENGLISH);

    @BeforeAll
    static void initReportFile() throws Exception {
        Files.createDirectories(REPORT_FILE.getParent());
        Files.writeString(
                REPORT_FILE,
                "BÁO CÁO CHI TIẾT DỮ LIỆU FAKER & DBUNIT (FR-01 ĐĂNG KÝ: TC01 -> TC17)\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * Nhóm 1 (TC01 -> TC06): EP Valid & BVA Valid (Min=8, Min+1=9, Max-1=31, Max=32).
     */
    static Stream<Arguments> validRegistrationCases() {
        String tc01Pass = STATIC_FAKER.regexify("[A-Z]{2}[a-z]{6}[0-9]{2}[!@#$%^&*]{2}"); // len = 12
        String tc02Pass = STATIC_FAKER.regexify("[A-Z][a-z]{5}[0-9][!@#$]");              // len = 8
        String tc03Pass = STATIC_FAKER.regexify("[A-Z][a-z]{5}[0-9][!@#$]");              // BVA Min = 8
        String tc04Pass = STATIC_FAKER.regexify("[A-Z][a-z]{6}[0-9][!@#$]");              // BVA Min + 1 = 9
        String tc05Pass = STATIC_FAKER.regexify("[A-Z][a-z]{28}[0-9][!@#$]");             // BVA Max - 1 = 31
        String tc06Pass = STATIC_FAKER.regexify("[A-Z][a-z]{29}[0-9][!@#$]");             // BVA Max = 32

        return Stream.of(
                Arguments.of(
                        "TC01 - EP Valid (Chuẩn 12 ký tự)",
                        STATIC_FAKER.internet().emailAddress(),
                        tc01Pass,
                        tc01Pass),
                Arguments.of(
                        "TC02 - EP Valid (Email có khoảng trắng 2 đầu và chữ HOA)",
                        "  " + STATIC_FAKER.internet().emailAddress().toUpperCase(Locale.ROOT) + "  ",
                        tc02Pass,
                        tc02Pass),
                Arguments.of(
                        "TC03 - BVA Valid (Biên dưới Min = 8 ký tự)",
                        STATIC_FAKER.internet().emailAddress(),
                        tc03Pass,
                        tc03Pass),
                Arguments.of(
                        "TC04 - BVA Valid (Trên biên dưới Min + 1 = 9 ký tự)",
                        STATIC_FAKER.internet().emailAddress(),
                        tc04Pass,
                        tc04Pass),
                Arguments.of(
                        "TC05 - BVA Valid (Dưới biên trên Max - 1 = 31 ký tự)",
                        STATIC_FAKER.internet().emailAddress(),
                        tc05Pass,
                        tc05Pass),
                Arguments.of(
                        "TC06 - BVA Valid (Biên trên Max = 32 ký tự)",
                        STATIC_FAKER.internet().emailAddress(),
                        tc06Pass,
                        tc06Pass)
        );
    }

    @Order(1)
    @ParameterizedTest(name = "{0}")
    @MethodSource("validRegistrationCases")
    @DisplayName("TC01 -> TC06 (EP & BVA Hợp lệ): Faker -> POST /api/register -> 201 Created -> DbUnit")
    void shouldRegisterValidUserAndMatchDynamicDbUnitDataset(
            String testCaseName, String rawEmail, String rawPassword, String confirmPassword) throws Exception {

        // 1. Gửi request tới Backend
        Map<String, Object> payload = orderedPayload(rawEmail, rawPassword, confirmPassword);
        ResponseEntity<Map<String, Object>> response = postRegister(payload);

        // 2. Kiểm tra HTTP Response
        assertEquals(201, response.getStatusCode().value(), testCaseName);
        assertEquals("Đăng ký tài khoản thành công!", response.getBody().get("message"));

        Map<?, ?> responseUser = (Map<?, ?>) response.getBody().get("user");
        assertNotNull(responseUser);

        long expectedId = ((Number) responseUser.get("id")).longValue();
        String expectedEmail = rawEmail.trim().toLowerCase(Locale.ROOT);
        String expectedName = expectedEmail.substring(0, expectedEmail.indexOf('@'));
        Integer expectedUserRoleId = roleId("user");

        // 3. Tạo Dynamic Expected Dataset trong DbUnit bằng ReplacementDataSet
        ReplacementDataSet expectedDataSet = loadReplacementDataSet("/datasets/expected-register-success.xml");
        expectedDataSet.addReplacementObject("[EXPECTED_ID]", expectedId);
        expectedDataSet.addReplacementObject("[EXPECTED_EMAIL]", expectedEmail);
        expectedDataSet.addReplacementObject("[EXPECTED_NAME]", expectedName);
        expectedDataSet.addReplacementObject("[EXPECTED_ROLE_ID]", expectedUserRoleId);
        expectedDataSet.addReplacementObject("[NULL]", null);

        // 4. Đọc Actual Dataset từ PostgreSQL qua DbUnit (từ dbUnitConnection của AbstractDbUnitTestSupport)
        IDataSet actualDataSet = dbUnitConnection.createDataSet();
        ITable actualUsersTable = actualDataSet.getTable("users");
        ITable actualUserInfoTable = actualDataSet.getTable("user_info");

        // In ra log dữ liệu Faker, Expected DbUnit Table và Actual Database Table trước khi dọn dẹp
        logTestSnapshot(testCaseName, payload, response, expectedDataSet, actualUsersTable, actualUserInfoTable);

        // 5. So sánh bảng users (lọc bỏ cột sinh ngẫu nhiên password_hash & created_at)
        ITable filteredActualUsers = DefaultColumnFilter.excludedColumnsTable(
                actualUsersTable, new String[]{"password_hash", "created_at"});
        Assertion.assertEquals(expectedDataSet.getTable("users"), filteredActualUsers);

        // Kiểm tra riêng cột password_hash bằng BCrypt và created_at != null trên dòng vừa tạo (row index = 1)
        String actualPasswordHash = (String) actualUsersTable.getValue(1, "password_hash");
        assertTrue(passwordEncoder.matches(rawPassword, actualPasswordHash),
                "password_hash trong DB phải khớp BCrypt với mật khẩu Faker đã sinh");
        assertNotNull(actualUsersTable.getValue(1, "created_at"),
                "created_at trong DB không được null");

        // 6. So sánh toàn bộ bảng user_info bằng DbUnit
        Assertion.assertEquals(expectedDataSet.getTable("user_info"), actualUserInfoTable);
    }

    /**
     * Nhóm 2 (TC07 -> TC13): BVA Invalid & EP Invalid về Mật khẩu và Định dạng Email.
     */
    static Stream<Arguments> invalidPasswordAndEmailFormatCases() {
        String validPass = STATIC_FAKER.regexify("[A-Z][a-z]{5}[0-9][!@#$]"); // 8 ký tự hợp lệ
        String len7Pass = STATIC_FAKER.regexify("[A-Z][a-z]{4}[0-9][!@#$]");  // BVA Min - 1 = 7
        String len33Pass = STATIC_FAKER.regexify("[A-Z][a-z]{30}[0-9][!@#$]"); // BVA Max + 1 = 33
        String noUpperPass = STATIC_FAKER.regexify("[a-z]{8}[0-9]{2}[!@#$]{2}");
        String noDigitPass = STATIC_FAKER.regexify("[A-Z]{2}[a-z]{8}[!@#$]{2}");
        String noSpecialPass = STATIC_FAKER.regexify("[A-Z]{2}[a-z]{8}[0-9]{2}");
        String unicodeOnlyPass = STATIC_FAKER.regexify("[A-Z][a-z]{6}[0-9]") + "ăêôđ";

        return Stream.of(
                Arguments.of(
                        "TC07 - BVA Invalid (Biên dưới Min - 1 = 7 ký tự)",
                        orderedPayload(STATIC_FAKER.internet().emailAddress(), len7Pass, len7Pass),
                        "Mật khẩu phải có độ dài từ 8 đến 32 ký tự."),
                Arguments.of(
                        "TC08 - BVA Invalid (Biên trên Max + 1 = 33 ký tự)",
                        orderedPayload(STATIC_FAKER.internet().emailAddress(), len33Pass, len33Pass),
                        "Mật khẩu phải có độ dài từ 8 đến 32 ký tự."),
                Arguments.of(
                        "TC09 - EP Invalid (Thiếu chữ cái in hoa A-Z)",
                        orderedPayload(STATIC_FAKER.internet().emailAddress(), noUpperPass, noUpperPass),
                        "Mật khẩu phải chứa ít nhất 1 chữ cái in hoa (A-Z)."),
                Arguments.of(
                        "TC10 - EP Invalid (Thiếu chữ số 0-9)",
                        orderedPayload(STATIC_FAKER.internet().emailAddress(), noDigitPass, noDigitPass),
                        "Mật khẩu phải chứa ít nhất 1 chữ số (0-9)."),
                Arguments.of(
                        "TC11 - EP Invalid (Thiếu ký tự đặc biệt)",
                        orderedPayload(STATIC_FAKER.internet().emailAddress(), noSpecialPass, noSpecialPass),
                        "Mật khẩu phải chứa ít nhất 1 ký tự đặc biệt (ví dụ: !@#$%^&*...)."),
                Arguments.of(
                        "TC12 - EP Invalid (Dùng chữ cái Unicode có dấu thay ký tự đặc biệt)",
                        orderedPayload(STATIC_FAKER.internet().emailAddress(), unicodeOnlyPass, unicodeOnlyPass),
                        "Mật khẩu phải chứa ít nhất 1 ký tự đặc biệt (ví dụ: !@#$%^&*...)."),
                Arguments.of(
                        "TC13 - EP Invalid (Email sai định dạng)",
                        orderedPayload(STATIC_FAKER.lorem().word() + "@invalid", validPass, validPass),
                        "Định dạng email không hợp lệ (Ví dụ: user@example.com).")
        );
    }

    @Order(2)
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPasswordAndEmailFormatCases")
    @DisplayName("TC07 -> TC13 (EP & BVA Không hợp lệ): Faker -> POST /api/register -> 400 Bad Request -> DbUnit")
    void shouldRejectInvalidPasswordAndEmailFormatCases(
            String testCaseName, Map<String, Object> invalidPayload, String expectedErrorMessage) throws Exception {
        assertRejectedWith400AndDatabaseUnchanged(testCaseName, invalidPayload, expectedErrorMessage);
    }

    @Order(3)
    @Test
    @DisplayName("TC14 - EP Invalid (Trùng Email): Đăng ký lần 2 với email trùng (khác hoa/thường) -> 409 Conflict -> DbUnit")
    void shouldRejectDuplicateEmailWith409AndNotInsertDuplicateRow() throws Exception {
        String rawEmail = faker.internet().emailAddress();
        String rawPassword = faker.regexify("[A-Z][a-z]{5}[0-9][!@#$]");

        // Bước 1: Đăng ký lần đầu thành công (201 Created)
        ResponseEntity<Map<String, Object>> firstResponse = postRegister(
                orderedPayload(rawEmail, rawPassword, rawPassword));
        assertEquals(201, firstResponse.getStatusCode().value());

        Map<?, ?> firstUser = (Map<?, ?>) firstResponse.getBody().get("user");
        long expectedId = ((Number) firstUser.get("id")).longValue();
        String expectedEmail = rawEmail.trim().toLowerCase(Locale.ROOT);
        String expectedName = expectedEmail.substring(0, expectedEmail.indexOf('@'));
        Integer expectedUserRoleId = roleId("user");

        // Bước 2: Đăng ký lần 2 với cùng email nhưng viết HOA và thêm khoảng trắng
        String newPassword = faker.regexify("[A-Z]{2}[a-z]{6}[0-9]{2}[!@#$]");
        Map<String, Object> duplicatePayload = orderedPayload(
                "  " + rawEmail.toUpperCase(Locale.ROOT) + " ",
                newPassword,
                newPassword);
        ResponseEntity<Map<String, Object>> duplicateResponse = postRegister(duplicatePayload);

        assertEquals(409, duplicateResponse.getStatusCode().value());
        assertEquals("Email này đã được sử dụng. Vui lòng chọn một email khác.",
                duplicateResponse.getBody().get("error"));

        // Bước 3: Dùng DbUnit xác nhận DB chỉ chứa đúng bản ghi lần 1, mật khẩu không bị ghi đè
        ReplacementDataSet expectedDataSet = loadReplacementDataSet("/datasets/expected-register-success.xml");
        expectedDataSet.addReplacementObject("[EXPECTED_ID]", expectedId);
        expectedDataSet.addReplacementObject("[EXPECTED_EMAIL]", expectedEmail);
        expectedDataSet.addReplacementObject("[EXPECTED_NAME]", expectedName);
        expectedDataSet.addReplacementObject("[EXPECTED_ROLE_ID]", expectedUserRoleId);
        expectedDataSet.addReplacementObject("[NULL]", null);

        IDataSet actualDataSet = dbUnitConnection.createDataSet();
        ITable actualUsersTable = actualDataSet.getTable("users");
        ITable actualUserInfoTable = actualDataSet.getTable("user_info");

        logTestSnapshot("TC14 - EP Invalid (Trùng Email khác hoa/thường)",
                duplicatePayload, duplicateResponse, expectedDataSet, actualUsersTable, actualUserInfoTable);

        ITable filteredActualUsers = DefaultColumnFilter.excludedColumnsTable(
                actualUsersTable, new String[]{"password_hash", "created_at"});

        Assertion.assertEquals(expectedDataSet.getTable("users"), filteredActualUsers);
        Assertion.assertEquals(expectedDataSet.getTable("user_info"), actualUserInfoTable);

        // Xác nhận mật khẩu vẫn là mật khẩu của lần đăng ký đầu tiên
        String actualPasswordHash = (String) actualUsersTable.getValue(1, "password_hash");
        assertTrue(passwordEncoder.matches(rawPassword, actualPasswordHash));
    }

    /**
     * Nhóm 4 (TC15 -> TC17): EP Invalid về Confirm Password, Trường rỗng và Sai kiểu dữ liệu.
     */
    static Stream<Arguments> invalidConfirmBlankAndTypeCases() {
        String validPass = STATIC_FAKER.regexify("[A-Z][a-z]{5}[0-9][!@#$]");

        return Stream.of(
                Arguments.of(
                        "TC15 - EP Invalid (Xác nhận mật khẩu không khớp)",
                        orderedPayload(STATIC_FAKER.internet().emailAddress(), validPass, validPass + "X"),
                        "Mật khẩu xác nhận không khớp với mật khẩu đã nhập."),
                Arguments.of(
                        "TC16 - EP Invalid (Trường bắt buộc để trống / chỉ chứa khoảng trắng)",
                        orderedPayload("   ", validPass, validPass),
                        "Vui lòng điền đầy đủ email, mật khẩu và xác nhận mật khẩu."),
                Arguments.of(
                        "TC17 - EP Invalid (Giá trị đầu vào không phải chuỗi - Non-string)",
                        orderedPayload(STATIC_FAKER.number().randomNumber(), List.of(validPass), true),
                        "Vui lòng điền đầy đủ email, mật khẩu và xác nhận mật khẩu.")
        );
    }

    @Order(4)
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidConfirmBlankAndTypeCases")
    @DisplayName("TC15 -> TC17 (EP Không hợp lệ): Faker -> POST /api/register -> 400 Bad Request -> DbUnit")
    void shouldRejectInvalidConfirmBlankAndTypeCases(
            String testCaseName, Map<String, Object> invalidPayload, String expectedErrorMessage) throws Exception {
        assertRejectedWith400AndDatabaseUnchanged(testCaseName, invalidPayload, expectedErrorMessage);
    }

    private void assertRejectedWith400AndDatabaseUnchanged(
            String testCaseName, Map<String, Object> invalidPayload, String expectedErrorMessage) throws Exception {

        // 1. Gửi request không hợp lệ tới Backend
        ResponseEntity<Map<String, Object>> response = postRegister(invalidPayload);

        // 2. Kiểm tra HTTP 400 & thông báo lỗi
        assertEquals(400, response.getStatusCode().value(), testCaseName);
        assertEquals(expectedErrorMessage, response.getBody().get("error"));

        // 3. Dùng DbUnit kiểm tra bảng users & user_info hoàn toàn không có dòng mới nào được ghi
        ReplacementDataSet expectedDataSet = loadReplacementDataSet("/datasets/expected-register-unchanged.xml");
        expectedDataSet.addReplacementObject("[NULL]", null);

        IDataSet actualDataSet = dbUnitConnection.createDataSet();
        ITable actualUsersTable = actualDataSet.getTable("users");
        ITable actualUserInfoTable = actualDataSet.getTable("user_info");

        // In ra log dữ liệu Faker, Expected DbUnit Table và Actual Database Table trước khi dọn dẹp
        logTestSnapshot(testCaseName, invalidPayload, response, expectedDataSet, actualUsersTable, actualUserInfoTable);

        ITable filteredActualUsers = DefaultColumnFilter.excludedColumnsTable(
                actualUsersTable, new String[]{"password_hash", "created_at"});

        Assertion.assertEquals(expectedDataSet.getTable("users"), filteredActualUsers);
        Assertion.assertEquals(expectedDataSet.getTable("user_info"), actualUserInfoTable);
    }

    private static Map<String, Object> orderedPayload(Object email, Object password, Object confirmPassword) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("email", email);
        payload.put("password", password);
        payload.put("confirmPassword", confirmPassword);
        return payload;
    }

    private ResponseEntity<Map<String, Object>> postRegister(Map<String, Object> body) {
        return request("/api/register", HttpMethod.POST, body, null);
    }

    private ReplacementDataSet loadReplacementDataSet(String resourcePath) throws Exception {
        try (InputStream inputStream = getClass().getResourceAsStream(resourcePath)) {
            assertNotNull(inputStream, "Không tìm thấy file dataset: " + resourcePath);
            IDataSet flatXmlDataSet = new FlatXmlDataSetBuilder()
                    .setColumnSensing(true)
                    .build(inputStream);
            return new ReplacementDataSet(flatXmlDataSet);
        }
    }

    /**
     * In ra log toàn bộ dữ liệu do Faker tạo ra, HTTP Response và bảng dữ liệu trong Database (DbUnit ITable).
     */
    private void logTestSnapshot(
            String testCaseName,
            Map<String, Object> fakerPayload,
            ResponseEntity<Map<String, Object>> response,
            IDataSet expectedDataSet,
            ITable actualUsersTable,
            ITable actualUserInfoTable) throws Exception {

        StringBuilder sb = new StringBuilder("\n");
        sb.append("================================================================================\n");
        sb.append(" KỊCH BẢN : ").append(testCaseName).append("\n");
        sb.append("--------------------------------------------------------------------------------\n");
        sb.append(" [1] DỮ LIỆU FAKER TẠO RA (REQUEST PAYLOAD):\n");
        fakerPayload.forEach((k, v) -> sb.append(String.format("     - %-16s : %s (len=%s)%n",
                k, formatValue(v), v instanceof String s ? s.length() : "N/A")));
        sb.append(" [2] KẾT QUẢ PHẢN HỒI TỪ BACKEND (HTTP RESPONSE):\n");
        sb.append("     - Status Code      : ").append(response.getStatusCode().value()).append("\n");
        sb.append("     - Response Body    : ").append(response.getBody()).append("\n");
        sb.append("--------------------------------------------------------------------------------\n");
        sb.append(" [3] BẢNG MONG ĐỢI ĐỘNG DBUNIT (EXPECTED DATASET):\n");
        sb.append(formatITable("expected.users", expectedDataSet.getTable("users")));
        sb.append(formatITable("expected.user_info", expectedDataSet.getTable("user_info")));
        sb.append("--------------------------------------------------------------------------------\n");
        sb.append(" [4] BẢNG THỰC TẾ TRONG POSTGRESQL (ACTUAL DATABASE):\n");
        sb.append(formatITable("actual.users", actualUsersTable));
        sb.append(formatITable("actual.user_info", actualUserInfoTable));
        sb.append("================================================================================");

        log.info(sb.toString());
        Files.writeString(
                REPORT_FILE,
                sb.append("\n").toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private static String formatITable(String title, ITable table) throws Exception {
        Column[] columns = table.getTableMetaData().getColumns();
        int rowCount = table.getRowCount();
        int colCount = columns.length;

        int[] widths = new int[colCount];
        String[] headers = new String[colCount];
        for (int c = 0; c < colCount; c++) {
            headers[c] = columns[c].getColumnName();
            widths[c] = headers[c].length();
        }

        List<String[]> rows = new ArrayList<>(rowCount);
        for (int r = 0; r < rowCount; r++) {
            String[] rowValues = new String[colCount];
            for (int c = 0; c < colCount; c++) {
                Object val = table.getValue(r, headers[c]);
                String text = val == null ? "NULL" : val.toString();
                if ("password_hash".equals(headers[c]) && text.length() > 25) {
                    text = text.substring(0, 22) + "...";
                }
                rowValues[c] = text;
                widths[c] = Math.max(widths[c], text.length());
            }
            rows.add(rowValues);
        }

        StringBuilder out = new StringBuilder();
        out.append("   * Bảng ").append(title).append(" (").append(rowCount).append(" dòng):\n");

        String border = buildBorder(widths);
        out.append("     ").append(border).append("\n");
        out.append("     ").append(buildRow(headers, widths)).append("\n");
        out.append("     ").append(border).append("\n");
        for (String[] row : rows) {
            out.append("     ").append(buildRow(row, widths)).append("\n");
        }
        out.append("     ").append(border).append("\n");
        return out.toString();
    }

    private static String buildBorder(int[] widths) {
        StringBuilder sb = new StringBuilder("+");
        for (int w : widths) {
            sb.append("-".repeat(w + 2)).append("+");
        }
        return sb.toString();
    }

    private static String buildRow(String[] values, int[] widths) {
        StringBuilder sb = new StringBuilder("|");
        for (int i = 0; i < widths.length; i++) {
            sb.append(" ").append(String.format("%-" + widths[i] + "s", values[i])).append(" |");
        }
        return sb.toString();
    }

    private static String formatValue(Object value) {
        if (value instanceof String s) {
            return "\"" + s + "\"";
        }
        return String.valueOf(value);
    }
}
