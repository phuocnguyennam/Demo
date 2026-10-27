package com.demo.auth.demo_dbunit;

import com.demo.auth.AbstractDbUnitTestSupport;
import java.util.Map;
import org.dbunit.database.DatabaseConfig;
import org.dbunit.dataset.IDataSet;
import org.dbunit.dataset.ITable;
import org.dbunit.dataset.ReplacementDataSet;
import org.dbunit.operation.DatabaseOperation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * KỊCH BẢN DEMO 01: DEMO CÀI ĐẶT, CẤU HÌNH KẾT NỐI VÀ KHỞI TẠO DỮ LIỆU MẪU (DATA SEEDING).
 *
 * Mục tiêu minh họa:
 * - Thiết lập kết nối IDatabaseConnection từ Spring DataSource (Testcontainers PostgreSQL).
 * - Cấu hình DatabaseConfig & PostgresqlDataTypeFactory.
 * - Đọc trạng thái Schema & dữ liệu khởi tạo ban đầu qua IDataSet / ITable.
 * - Sử dụng DatabaseOperation (REFRESH, DELETE) để nạp dữ liệu mẫu từ XML vào DB trước khi gọi API.
 */
class DbUnitDemo01SetupAndSeedingTest extends AbstractDbUnitTestSupport {

    @Test
    @DisplayName("Demo 1.1: Kiểm tra cài đặt kết nối DBUnit, đọc Schema và dữ liệu khởi tạo ban đầu")
    void verifyDbUnitConnectionSetupAndInitialDatabaseState() throws Exception {
        // 1. Kiểm tra DataTypeFactory đã được cấu hình cho PostgreSQL
        Object dataTypeFactory = dbUnitConnection.getConfig()
                .getProperty(DatabaseConfig.PROPERTY_DATATYPE_FACTORY);
        assertNotNull(dataTypeFactory, "PostgresqlDataTypeFactory phải được thiết lập trong DatabaseConfig");

        // 2. Trích xuất toàn bộ IDataSet thực tế từ PostgreSQL thông qua DBUnit
        IDataSet actualDataSet = dbUnitConnection.createDataSet();

        // 3. Xác minh bảng roles có đúng 2 vai trò mặc định ('user' và 'admin')
        ITable rolesTable = actualDataSet.getTable("roles");
        assertEquals(2, rolesTable.getRowCount(), "Bảng roles phải có sẵn 2 vai trò");
        assertEquals("user", rolesTable.getValue(0, "role_name"));
        assertEquals("admin", rolesTable.getValue(1, "role_name"));

        // 4. Xác minh bảng users và user_info có sẵn tài khoản Admin mặc định từ DatabaseInitializer
        ITable usersTable = actualDataSet.getTable("users");
        assertEquals(1, usersTable.getRowCount(), "Ban đầu bảng users chỉ chứa 1 tài khoản admin");
        assertEquals(ADMIN_EMAIL, usersTable.getValue(0, "email"));

        ITable userInfoTable = actualDataSet.getTable("user_info");
        assertEquals(1, userInfoTable.getRowCount());
        assertEquals("Admin", userInfoTable.getValue(0, "name"));
        assertNull(userInfoTable.getValue(0, "phone_number"));

        // 5. Xác minh bảng edit_history khởi tạo rỗng
        ITable historyTable = actualDataSet.getTable("edit_history");
        assertEquals(0, historyTable.getRowCount());
    }

    @Test
    @DisplayName("Demo 1.2: Sử dụng DatabaseOperation của DBUnit để nạp dữ liệu mẫu (Seed XML) phục vụ gọi API")
    void seedUsersViaDbUnitDatabaseOperationAndVerifyViaApi() throws Exception {
        // 1. Dùng Faker sinh một mật khẩu chuẩn, băm bằng BCrypt để đưa vào XML Seed Dataset
        String rawPassword = generateValidPassword();
        String bcryptHash = passwordEncoder.encode(rawPassword);

        ReplacementDataSet seedDataSet = loadReplacementDataSet(
                "/datasets/demo_dbunit/demo01-seed-users.xml",
                Map.of(
                        "${bcrypt_password}", bcryptHash,
                        "${user_role_id}", String.valueOf(roleId("user"))
                )
        );

        // 2. Thực thi DatabaseOperation.REFRESH để chèn/cập nhật 2 người dùng mẫu từ XML vào PostgreSQL
        DatabaseOperation.REFRESH.execute(dbUnitConnection, seedDataSet);

        // 3. Dùng DBUnit xác nhận bảng users hiện có 3 dòng (1 admin mặc định + 2 user vừa nạp từ XML)
        ITable usersTableAfterSeed = dbUnitConnection.createDataSet().getTable("users");
        assertEquals(3, usersTableAfterSeed.getRowCount());

        // 4. Gọi API POST /api/login bằng tài khoản vừa được DBUnit seed ("seeded.user1@example.com")
        ResponseEntity<Map<String, Object>> loginRes = request(
                "/api/login",
                HttpMethod.POST,
                Map.of("email", "seeded.user1@example.com", "password", rawPassword),
                null
        );
        assertEquals(200, loginRes.getStatusCode().value());
        String token = (String) loginRes.getBody().get("token");
        assertNotNull(token);

        // 5. Gọi API GET /api/profile để xác nhận dữ liệu hồ sơ khớp với file demo01-seed-users.xml
        ResponseEntity<Map<String, Object>> profileRes = request(
                "/api/profile",
                HttpMethod.GET,
                null,
                token
        );
        assertEquals(200, profileRes.getStatusCode().value());
        Map<?, ?> profile = (Map<?, ?>) profileRes.getBody().get("profile");
        assertEquals("Seeded User One", profile.get("name"));
        assertEquals("+84 901 111 222", profile.get("phoneNumber"));

        // 6. Demo dọn dẹp dữ liệu vừa seed bằng DatabaseOperation.DELETE
        DatabaseOperation.DELETE.execute(dbUnitConnection, seedDataSet);
        assertEquals(1, dbUnitConnection.createDataSet().getTable("users").getRowCount());
    }
}
