package com.demo.auth;

import java.util.Locale;
import java.util.Map;
import org.dbunit.dataset.IDataSet;
import org.dbunit.dataset.ITable;
import org.dbunit.dataset.ReplacementDataSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KỊCH BẢN DEMO 02: DEMO CÁC CHỨC NĂNG CƠ BẢN CỦA DBUNIT TRONG LUỒNG FAKER -> API -> DBUNIT.
 *
 * Mục tiêu minh họa:
 * - Sinh dữ liệu tự động bằng Faker.
 * - Gọi API đơn lẻ (POST /api/register, PUT /api/profile).
 * - Đối chiếu toàn bộ bảng bằng FlatXmlDataSet + ReplacementDataSet + DefaultColumnFilter + Assertion.assertEquals.
 * - Truy vấn đối chiếu có điều kiện bằng createQueryTable và xác minh DB không bị ghi rác khi API trả lỗi.
 */
class DbUnitDemo02BasicCrudVerificationTest extends AbstractDbUnitTestSupport {

    @Test
    @DisplayName("Demo 2.1: Faker sinh dữ liệu -> Gọi POST /api/register -> DBUnit đối chiếu bảng users & user_info")
    void registerWithFakerDataAndVerifyDatabaseTables() throws Exception {
        // 1. Dùng Faker sinh dữ liệu đăng ký tự động (có chữ hoa và khoảng trắng để kiểm tra chuẩn hóa email)
        String rawEmail = "  " + faker.internet().emailAddress().toUpperCase(Locale.ROOT) + "  ";
        String normalizedEmail = rawEmail.trim().toLowerCase(Locale.ROOT);
        String expectedDefaultName = normalizedEmail.substring(0, normalizedEmail.indexOf('@'));
        String password = generateValidPassword();

        // 2. Gọi API POST /api/register
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

        // 3. Nạp Expected Dataset từ file XML mẫu và truyền các biến Faker vào ReplacementDataSet
        ReplacementDataSet expectedDataSet = loadReplacementDataSet(
                "/datasets/expected-register.xml",
                Map.of(
                        "${email}", normalizedEmail,
                        "${default_name}", expectedDefaultName,
                        "${user_role_id}", String.valueOf(roleId("user")),
                        "${admin_role_id}", String.valueOf(roleId("admin"))
                )
        );

        IDataSet actualDataSet = dbUnitConnection.createDataSet();

        // Đối chiếu bảng users (lọc bỏ các cột tự sinh id, password_hash, created_at)
        assertTableEquals(
                expectedDataSet.getTable("users"),
                actualDataSet.getTable("users"),
                new String[]{"id"},
                new String[]{"id", "password_hash", "created_at"}
        );

        // Kiểm tra mật khẩu băm trong DB khớp với password gốc do Faker tạo
        String storedHash = (String) actualDataSet.getTable("users").getValue(1, "password_hash");
        assertNotNull(storedHash);
        assertTrue(passwordEncoder.matches(password, storedHash));

        // Đối chiếu bảng user_info
        assertTableEquals(
                expectedDataSet.getTable("user_info"),
                actualDataSet.getTable("user_info"),
                new String[]{"id"},
                new String[]{"id"}
        );
    }

    @Test
    @DisplayName("Demo 2.2: Faker sinh dữ liệu -> Gọi PUT /api/profile -> DBUnit đối chiếu user_info & edit_history")
    void updateProfileWithFakerDataAndVerifyDatabaseTables() throws Exception {
        // 1. Dùng Faker sinh dữ liệu tài khoản và dữ liệu cập nhật hồ sơ
        String email = faker.internet().emailAddress().toLowerCase(Locale.ROOT);
        String initialName = email.substring(0, email.indexOf('@'));
        String password = generateValidPassword();
        String updatedName = faker.name().fullName();
        String updatedPhone = faker.numerify("+84 9## ### ###");

        // 2. Đăng ký, đăng nhập và gọi PUT /api/profile
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
        String token = (String) loginRes.getBody().get("token");

        ResponseEntity<Map<String, Object>> updateRes = request(
                "/api/profile",
                HttpMethod.PUT,
                Map.of("name", updatedName, "phoneNumber", updatedPhone),
                token
        );
        assertEquals(200, updateRes.getStatusCode().value());

        // 3. Đối chiếu kết quả trong DB bằng DBUnit
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

        assertTableEquals(
                expectedDataSet.getTable("user_info"),
                actualDataSet.getTable("user_info"),
                new String[]{"id"},
                new String[]{"id"}
        );

        assertTableEquals(
                expectedDataSet.getTable("edit_history"),
                actualDataSet.getTable("edit_history"),
                new String[]{"history_id"},
                new String[]{"history_id", "edited_at"}
        );
    }

    @Test
    @DisplayName("Demo 2.3: Dùng createQueryTable truy vấn có điều kiện và xác minh DB không phát sinh bản ghi rác khi đăng ký trùng email")
    void verifyQueryTableAndNoSideEffectsOnDuplicateRegistration() throws Exception {
        String email = faker.internet().emailAddress().toLowerCase(Locale.ROOT);
        String password = generateValidPassword();

        // Đăng ký lần 1 thành công (201 Created)
        assertEquals(201, request(
                "/api/register",
                HttpMethod.POST,
                Map.of("email", email, "password", password, "confirmPassword", password),
                null
        ).getStatusCode().value());

        // Đăng ký lần 2 với cùng email (409 Conflict)
        assertEquals(409, request(
                "/api/register",
                HttpMethod.POST,
                Map.of("email", email, "password", password, "confirmPassword", password),
                null
        ).getStatusCode().value());

        // Sử dụng createQueryTable của DBUnit để truy vấn kết hợp (JOIN) giữa users, user_info và roles
        ITable joinedQueryTable = dbUnitConnection.createQueryTable(
                "user_profile_join",
                String.format("""
                        SELECT u.email, ui.name, r.role_name
                        FROM users u
                        JOIN user_info ui ON ui.id = u.id
                        JOIN roles r ON r.role_id = ui.user_role
                        WHERE u.email = '%s'
                        """, email)
        );

        // Xác nhận chỉ có duy nhất 1 bản ghi được tạo và mang đúng role 'user'
        assertEquals(1, joinedQueryTable.getRowCount());
        assertEquals(email, joinedQueryTable.getValue(0, "email"));
        assertEquals(email.substring(0, email.indexOf('@')), joinedQueryTable.getValue(0, "name"));
        assertEquals("user", joinedQueryTable.getValue(0, "role_name"));
    }
}

