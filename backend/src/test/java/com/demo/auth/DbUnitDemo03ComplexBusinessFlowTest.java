package com.demo.auth;

import java.util.Locale;
import java.util.Map;
import org.dbunit.Assertion;
import org.dbunit.dataset.IDataSet;
import org.dbunit.dataset.ITable;
import org.dbunit.dataset.ReplacementDataSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * KỊCH BẢN DEMO 03: DEMO CÁC LUỒNG XỬ LÝ NGHIỆP VỤ PHỨC TẠP VẬN DỤNG CASE STUDY (FR-01 -> FR-04).
 *
 * Mục tiêu minh họa:
 * - Kiểm thử chuỗi tác vụ đa tác nhân (User & Admin): Đăng ký -> User tự cập nhật -> Admin tìm kiếm & nâng quyền ->
 *   User (nay là Admin) tiếp tục cập nhật.
 * - Dùng DBUnit kiểm chứng quy tắc nghiệp vụ phức tạp ở tầng DB:
 *   + Cột actor_name trong bảng edit_history phải chụp tại thời điểm chỉnh sửa (bất biến dù người đó đổi tên sau này).
 *   + Cột changed_fields (JSONB) chỉ lưu các trường thực sự thay đổi (diff before/after).
 * - Kiểm chứng bảo mật phân quyền (RBAC) và quy tắc Admin không thể tự hạ quyền: đảm bảo 100% không có thay đổi ngầm
 *   trong DB khi request bị từ chối (403 Forbidden / 400 Bad Request).
 */
class DbUnitDemo03ComplexBusinessFlowTest extends AbstractDbUnitTestSupport {

    @Test
    @DisplayName("Demo 3.1: Luồng đa tác nhân (User & Admin), nâng quyền RBAC và kiểm chứng lịch sử chỉnh sửa bất biến (actor_name snapshot)")
    void verifyMultiActorRolePromotionAndImmutableActorNameHistoryInDatabase() throws Exception {
        // 1. Dùng Faker sinh dữ liệu cho toàn bộ kịch bản 3 bước chỉnh sửa
        String userEmail = faker.internet().emailAddress().toLowerCase(Locale.ROOT);
        String initialName = userEmail.substring(0, userEmail.indexOf('@'));
        String userPassword = generateValidPassword();

        String selfUpdatedName = faker.name().fullName();
        String selfUpdatedPhone = faker.numerify("+84 91# ### ###");

        String adminAssignedName = faker.name().fullName() + " (Verified)";
        String adminAssignedPhone = faker.numerify("+84 98# ### ###");

        String finalUserName = faker.name().fullName() + " (Admin)";

        // Bước 1: User đăng ký, đăng nhập và tự cập nhật hồ sơ lần 1 (đổi tên + thêm SĐT)
        ResponseEntity<Map<String, Object>> regRes = request(
                "/api/register",
                HttpMethod.POST,
                Map.of("email", userEmail, "password", userPassword, "confirmPassword", userPassword),
                null
        );
        assertEquals(201, regRes.getStatusCode().value());
        long userId = ((Number) ((Map<?, ?>) regRes.getBody().get("user")).get("id")).longValue();

        String userToken = (String) request(
                "/api/login",
                HttpMethod.POST,
                Map.of("email", userEmail, "password", userPassword),
                null
        ).getBody().get("token");

        assertEquals(200, request(
                "/api/profile",
                HttpMethod.PUT,
                Map.of("name", selfUpdatedName, "phoneNumber", selfUpdatedPhone),
                userToken
        ).getStatusCode().value());

        // Bước 2: Admin đăng nhập, sửa hồ sơ của User và nâng vai trò từ 'user' lên 'admin'
        String adminToken = (String) request(
                "/api/login",
                HttpMethod.POST,
                Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD),
                null
        ).getBody().get("token");
        long adminId = adminUserId();

        assertEquals(200, request(
                "/api/profile",
                HttpMethod.PUT,
                Map.of(
                        "userId", userId,
                        "name", adminAssignedName,
                        "phoneNumber", adminAssignedPhone,
                        "role", "admin"
                ),
                adminToken
        ).getStatusCode().value());

        // Bước 3: User (nay đã có role 'admin' trong DB) tự đổi tên lần 2 nhưng giữ nguyên SĐT
        // Gọi thêm 1 lần PUT không thay đổi gì (No-op) để kiểm chứng DB không sinh bản ghi lịch sử thừa
        assertEquals(200, request(
                "/api/profile",
                HttpMethod.PUT,
                Map.of("name", finalUserName, "phoneNumber", adminAssignedPhone),
                userToken
        ).getStatusCode().value());

        assertEquals(200, request(
                "/api/profile",
                HttpMethod.PUT,
                Map.of("name", finalUserName, "phoneNumber", adminAssignedPhone),
                userToken
        ).getStatusCode().value());

        // Bước 4: Dùng DBUnit đối chiếu toàn bộ 3 bảng (users, user_info, edit_history)
        String diffStep1 = String.format(
                "{\"name\": {\"after\": \"%s\", \"before\": \"%s\"}, \"phone_number\": {\"after\": \"%s\", \"before\": null}}",
                selfUpdatedName, initialName, selfUpdatedPhone
        );
        String diffStep2 = String.format(
                "{\"name\": {\"after\": \"%s\", \"before\": \"%s\"}, \"role\": {\"after\": \"admin\", \"before\": \"user\"}, \"phone_number\": {\"after\": \"%s\", \"before\": \"%s\"}}",
                adminAssignedName, selfUpdatedName, adminAssignedPhone, selfUpdatedPhone
        );
        String diffStep3 = String.format(
                "{\"name\": {\"after\": \"%s\", \"before\": \"%s\"}}",
                finalUserName, adminAssignedName
        );

        ReplacementDataSet expectedDataSet = loadReplacementDataSet(
                "/datasets/demo03-expected-complex-flow.xml",
                Map.ofEntries(
                        Map.entry("${user_email}", userEmail),
                        Map.entry("${final_user_name}", finalUserName),
                        Map.entry("${admin_assigned_phone}", adminAssignedPhone),
                        Map.entry("${admin_role_id}", String.valueOf(roleId("admin"))),
                        Map.entry("${user_id}", String.valueOf(userId)),
                        Map.entry("${admin_id}", String.valueOf(adminId)),
                        Map.entry("${initial_name}", initialName),
                        Map.entry("${admin_assigned_name}", adminAssignedName),
                        Map.entry("${diff_step_1}", diffStep1),
                        Map.entry("${diff_step_2}", diffStep2),
                        Map.entry("${diff_step_3}", diffStep3)
                )
        );

        IDataSet actualDataSet = dbUnitConnection.createDataSet();

        // Đối chiếu bảng users
        assertTableEquals(
                expectedDataSet.getTable("users"),
                actualDataSet.getTable("users"),
                new String[]{"id"},
                new String[]{"id", "password_hash", "created_at"}
        );

        // Đối chiếu bảng user_info (xác nhận user đã lên role admin và mang tên cuối cùng)
        assertTableEquals(
                expectedDataSet.getTable("user_info"),
                actualDataSet.getTable("user_info"),
                new String[]{"id"},
                new String[]{"id"}
        );

        // Đối chiếu bảng edit_history: đúng 3 dòng (lần gọi PUT thứ 4 không đổi dữ liệu nên không sinh lịch sử),
        // actor_name ở bước 1 vẫn giữ nguyên initialName dù user đã đổi tên 2 lần.
        assertTableEquals(
                expectedDataSet.getTable("edit_history"),
                actualDataSet.getTable("edit_history"),
                new String[]{"history_id"},
                new String[]{"history_id", "edited_at"}
        );
    }

    @Test
    @DisplayName("Demo 3.2: Kiểm chứng bảo mật phân quyền (RBAC) - Không có tác dụng phụ lên DB khi thao tác trái phép")
    void verifyDatabaseRemainsUnchangedOnUnauthorizedAndForbiddenOperations() throws Exception {
        // 1. Tạo 1 user thường bằng Faker
        String userEmail = faker.internet().emailAddress().toLowerCase(Locale.ROOT);
        String userPassword = generateValidPassword();

        ResponseEntity<Map<String, Object>> regRes = request(
                "/api/register",
                HttpMethod.POST,
                Map.of("email", userEmail, "password", userPassword, "confirmPassword", userPassword),
                null
        );
        long userId = ((Number) ((Map<?, ?>) regRes.getBody().get("user")).get("id")).longValue();

        String userToken = (String) request(
                "/api/login",
                HttpMethod.POST,
                Map.of("email", userEmail, "password", userPassword),
                null
        ).getBody().get("token");

        String adminToken = (String) request(
                "/api/login",
                HttpMethod.POST,
                Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD),
                null
        ).getBody().get("token");
        long adminId = adminUserId();

        // 2. Chụp lại trạng thái bảng user_info và edit_history TRƯỚC khi thực hiện các lời gọi vi phạm
        ITable userInfoBefore = dbUnitConnection.createQueryTable(
                "user_info_snapshot",
                "SELECT id, name, phone_number, user_role FROM user_info ORDER BY id"
        );

        // 3. Thực hiện 3 hành vi vi phạm quy tắc nghiệp vụ:
        // (a) User thường cố sửa tên của Admin -> 403 Forbidden
        assertEquals(403, request(
                "/api/profile",
                HttpMethod.PUT,
                Map.of("userId", adminId, "name", "Hacked Admin Name"),
                userToken
        ).getStatusCode().value());

        // (b) User thường cố tự nâng quyền của chính mình lên 'admin' -> 403 Forbidden
        assertEquals(403, request(
                "/api/profile",
                HttpMethod.PUT,
                Map.of("userId", userId, "name", "Self Promoted", "role", "admin"),
                userToken
        ).getStatusCode().value());

        // (c) Admin cố tự hạ quyền của chính mình xuống 'user' -> 400 Bad Request
        assertEquals(400, request(
                "/api/profile",
                HttpMethod.PUT,
                Map.of("userId", adminId, "name", "Demoted Admin", "role", "user"),
                adminToken
        ).getStatusCode().value());

        // 4. Dùng DBUnit đối chiếu trạng thái DB SAU các lời gọi vi phạm: phải giống hệt trạng thái trước đó
        ITable userInfoAfter = dbUnitConnection.createQueryTable(
                "user_info_snapshot",
                "SELECT id, name, phone_number, user_role FROM user_info ORDER BY id"
        );
        Assertion.assertEquals(userInfoBefore, userInfoAfter);
        assertEquals(0, dbUnitConnection.createDataSet().getTable("edit_history").getRowCount());
    }
}
