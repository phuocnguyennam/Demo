---
name: fr-faker-dbunit-test
description: Tự động thiết kế kịch bản EP & BVA, tạo Dataset XML động và sinh lớp kiểm thử tích hợp FR_<Name>_Test.java sử dụng Datafaker, Spring Boot TestRestTemplate, Testcontainers PostgreSQL và DbUnit theo đúng kiến trúc của FR_Register_Test.java. Kích hoạt skill này bất cứ khi nào người dùng yêu cầu tạo test cho FR-01, FR-02 (Login), FR-03 (Profile), FR-04 (Edit History) hoặc một yêu cầu chức năng (FR) mới.
---

# FR Faker + DbUnit Integration Test Generator Skill

Skill này hướng dẫn **GitHub Copilot Agent** tự động phân tích một Yêu cầu chức năng (**Functional Requirement — FR**) trong dự án và sinh ra bộ kiểm thử tích hợp hoàn chỉnh **giống hệt chuẩn kiến trúc của [`FR_Register_Test.java`](../../../backend/src/test/java/com/demo/auth/FR_Register_Test.java)**.

---

## 1. Khi nào sử dụng Skill này (Trigger Conditions)
Kích hoạt skill này khi người dùng nhập các prompt như:
- *"Tạo file test cho FR-02 (Đăng nhập) giống `FR_Register_Test.java`"*
- *"Áp dụng EP & BVA với Faker và DbUnit để viết `FR_Profile_Test.java` cho FR-03"*
- *"Viết test cho FR-04 (`FR_History_Test.java`) theo chuẩn `FR_Register_Test`"*
- Hoặc bất kỳ yêu cầu nào đề cập đến việc tạo kiểm thử `FR_*_Test.java` kết hợp **EP/BVA + Datafaker + Testcontainers + DbUnit**.

---

## 2. Kiến trúc chuẩn bắt buộc (Chuẩn `FR_Register_Test.java`)

Mọi lớp kiểm thử được tạo bởi skill này **BẮT BUỘC** phải tuân thủ 7 quy tắc kiến trúc sau:

1. **Vị trí & Đặt tên file**:
   - File Java đặt tại: `backend/src/test/java/com/demo/auth/FR_<FeatureName>_Test.java` (ví dụ: `FR_Login_Test.java`, `FR_Profile_Test.java`, `FR_History_Test.java`).
   - Package: `package com.demo.auth;`
   - File Dataset XML đặt tại: `backend/src/test/resources/datasets/expected-<feature>-*.xml`.
   - File Log báo cáo xuất ra tại: `Path.of("target", "faker-dbunit-<feature>-report.log")` (hoặc dùng chung `target/faker-dbunit-report.log`).

2. **Kế thừa lớp cơ sở [`AbstractDbUnitTestSupport`](../../../backend/src/test/java/com/demo/auth/AbstractDbUnitTestSupport.java)**:
   - Khai báo:
     ```java
     @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
     class FR_<FeatureName>_Test extends AbstractDbUnitTestSupport
     ```
   - **KHÔNG** khai báo lại `@Container PostgreSQLContainer`, `@DynamicPropertySource`, `TestRestTemplate`, `DataSource`, hay `@BeforeEach` khởi tạo `dbUnitConnection` (vì `AbstractDbUnitTestSupport` đã cung cấp sẵn: `restTemplate`, `jdbcTemplate`, `dbUnitConnection`, `faker`, `passwordEncoder`, `ADMIN_EMAIL`, `ADMIN_PASSWORD`, `request(path, method, body, token)`, `roleId(roleName)`, `adminUserId()`, `generateValidPassword()`).
   - Lưu ý trạng thái DB đầu mỗi test (`@BeforeEach` của `AbstractDbUnitTestSupport`):
     - Bảng `roles` có 2 dòng: `user` (`role_id = 1`) và `admin` (`role_id = 2`).
     - Bảng `users` và `user_info` có sẵn duy nhất 1 tài khoản Admin mặc định (`id = 1`, `email = "admin@example.com"`, `name = "Admin"`, `phone_number = null`, `user_role = 2`).
     - Bảng `edit_history` rỗng (`0` dòng).

3. **Thứ tự thực thi tuần tự (`TC01` → `TCxx`)**:
   - Dùng `@TestMethodOrder(MethodOrderer.OrderAnnotation.class)` và chia các kịch bản thành các hàm gắn `@Order(1)`, `@Order(2)`, `@Order(3)`... để đảm bảo khi chạy test và khi ghi ra file báo cáo log luôn xuất hiện đúng thứ tự tăng dần `TC01` → `TCxx`.
   - Đặt tên hiển thị của `@ParameterizedTest(name = "{0}")` với tham số đầu tiên luôn là `String testCaseName` có dạng `"TC01 - <Kỹ thuật EP/BVA> (<Mô tả ngắn>)"`.

4. **Sinh dữ liệu Data-Driven bằng `Datafaker` áp dụng EP & BVA**:
   - Khai báo `private static final Faker STATIC_FAKER = new Faker(Locale.ENGLISH);` để dùng trong các hàm `static Stream<Arguments>` của `@MethodSource`.
   - Không gán cứng (hardcode) email, mật khẩu, tên, số điện thoại. Luôn dùng `STATIC_FAKER.internet().emailAddress()`, `STATIC_FAKER.regexify(...)`, `STATIC_FAKER.name().fullName()`, `STATIC_FAKER.numerify(...)`.
   - Với các trường có giới hạn độ dài (BVA), dùng `STATIC_FAKER.regexify(...)` để sinh chính xác độ dài tại các điểm biên: `Min - 1`, `Min`, `Min + 1`, `Max - 1`, `Max`, `Max + 1`.
   - Luôn tạo Request Payload bằng `LinkedHashMap` (`orderedPayload(...)`) để giữ nguyên thứ tự các trường khi in ra bảng log.

5. **Kiểm chứng trạng thái CSDL bằng DbUnit (`ReplacementDataSet` + `DefaultColumnFilter`)**:
   - Đọc Dataset XML mẫu bằng `FlatXmlDataSetBuilder().setColumnSensing(true).build(is)` bọc trong `ReplacementDataSet`.
   - Gán các placeholder động tại runtime: `[EXPECTED_ID]`, `[EXPECTED_EMAIL]`, `[EXPECTED_NAME]`, `[EXPECTED_PHONE]`, `[EXPECTED_ROLE_ID]`, `[EXPECTED_CHANGED_FIELDS]`, `[NULL]` (`addReplacementObject("[NULL]", null)`).
   - Đọc trạng thái thực tế từ PostgreSQL: `IDataSet actualDataSet = dbUnitConnection.createDataSet();`.
   - Lọc bỏ các cột sinh ngẫu nhiên/thời gian thực (`password_hash`, `created_at`, `edited_at`, `history_id`) bằng `DefaultColumnFilter.excludedColumnsTable(...)` trước khi gọi `Assertion.assertEquals(expectedTable, filteredActualTable)`.
   - Với các kịch bản lỗi (`400 Bad Request`, `401 Unauthorized`, `403 Forbidden`, `404 Not Found`, `409 Conflict`), bắt buộc dùng DbUnit kiểm chứng các bảng `users`, `user_info`, `edit_history` **không bị thay đổi ngoài ý muốn** (không phát sinh dòng rác).

6. **Chụp Snapshot & Ghi báo cáo bảng ASCII (`logTestSnapshot` & `formatITable`)**:
   - Giữ nguyên bộ hàm `logTestSnapshot(...)` và `formatITable(...)` (xem mẫu trong [`templates/FR_Template_Test.java`](templates/FR_Template_Test.java)) để mỗi kịch bản `TC01` → `TCxx` đều xuất ra Console và file `target/faker-dbunit-<feature>-report.log` đủ 4 mục:
     - `[1] DỮ LIỆU FAKER TẠO RA (REQUEST PAYLOAD / PARAMS)`
     - `[2] KẾT QUẢ PHẢN HỒI TỪ BACKEND (HTTP RESPONSE)`
     - `[3] BẢNG MONG ĐỢI ĐỘNG DBUNIT (EXPECTED DATASET)`
     - `[4] BẢNG THỰC TẾ TRONG POSTGRESQL (ACTUAL DATABASE)`

7. **Tự động chạy kiểm chứng sau khi tạo file**:
   - Sau khi tạo xong file XML và `FR_<FeatureName>_Test.java`, luôn chạy lệnh Maven để xác nhận biên dịch và pass 100%:
     ```powershell
     mvn -f backend\pom.xml -Dtest=FR_<FeatureName>_Test test
     ```

---

## 3. Quy trình 5 Bước dành cho Copilot Agent khi nhận Prompt

Khi người dùng yêu cầu tạo test cho một FR bất kỳ:

1. **Bước 1 — Đọc đặc tả & Mã nguồn Controller của FR đó**:
   - Đọc mục FR tương ứng trong [`README.md`](../../../README.md).
   - Đọc Controller xử lý FR đó trong `backend/src/main/java/com/demo/auth/` ([`AuthController.java`](../../../backend/src/main/java/com/demo/auth/AuthController.java) cho `FR-01`, `FR-02` hoặc [`ProfileController.java`](../../../backend/src/main/java/com/demo/auth/ProfileController.java) cho `FR-03`, `FR-04`) để lấy **chính xác 100% HTTP Status Code và chuỗi thông báo lỗi tiếng Việt**.
2. **Bước 2 — Thiết kế danh sách Test Cases (`TC01` → `TCxx`) theo EP & BVA**:
   - Phân chia rõ:
     - **Nhóm `@Order(1)`**: Các kịch bản Hợp lệ (EP Valid & BVA Valid tại các biên `Min`, `Min+1`, `Max-1`, `Max`).
     - **Nhóm `@Order(2)`**: Các kịch bản Không hợp lệ về biên độ dài & định dạng (BVA Invalid `Min-1`, `Max+1` & EP Invalid).
     - **Nhóm `@Order(3)`**: Các kịch bản Không hợp lệ về xác thực / phân quyền RBAC / xung đột dữ liệu (`401`, `403`, `404`, `409`).
     - **Nhóm `@Order(4)`**: Các kịch bản Không hợp lệ về trường bắt buộc rỗng / khoảng trắng / sai kiểu dữ liệu (Non-string).
3. **Bước 3 — Tạo các file Dataset XML mẫu trong `backend/src/test/resources/datasets/`**:
   - Tạo file XML cho trạng thái thành công và trạng thái không đổi (Unchanged) với các placeholder `[EXPECTED_*]` và `[NULL]`.
4. **Bước 4 — Tạo lớp `FR_<FeatureName>_Test.java`**:
   - Dựa trên khuôn mẫu [`templates/FR_Template_Test.java`](templates/FR_Template_Test.java) và [`FR_Register_Test.java`](../../../backend/src/test/java/com/demo/auth/FR_Register_Test.java).
5. **Bước 5 — Thực thi `mvn test` và kiểm tra file log báo cáo**:
   - Chạy lệnh Maven test, đảm bảo `Failures: 0, Errors: 0` và kiểm tra file log trong `backend/target/`.

---

## 4. Bảng Tham Chiếu Nhanh các FR trong Dự Án (Chuẩn xác 100% theo Code Backend)

Sử dụng bảng tra cứu dưới đây để đảm bảo khớp tuyệt đối với mã nguồn [`AuthController.java`](../../../backend/src/main/java/com/demo/auth/AuthController.java) và [`ProfileController.java`](../../../backend/src/main/java/com/demo/auth/ProfileController.java):

### 4.1. FR-02: Chức năng Đăng nhập (`POST /api/login` & `GET /api/me`) — Gợi ý tên lớp: `FR_Login_Test.java`
- **Setup dữ liệu mồi (Pre-condition)**: Trước khi gọi `/api/login`, có thể dùng tài khoản Admin có sẵn hoặc đăng ký/seed 1 tài khoản User bằng dữ liệu Faker (hoặc `DatabaseOperation.REFRESH`).
- **Quy tắc & Thông báo thực tế từ `AuthController.java`**:
  - Thành công (`200 OK`):
    - `POST /api/login`: Trả về `{ "message": "Đăng nhập thành công!", "token": "<jwt>", "user": { "id": ..., "email": ..., "createdAt": ... } }`. Hỗ trợ email có khoảng trắng 2 đầu và chữ HOA (`trim` + `lowercase`).
    - `GET /api/me` (với header `Authorization: Bearer <token>`): Trả về `200 OK` `{ "user": { "id": ..., "email": ..., "createdAt": ... } }`.
  - Thiếu email hoặc mật khẩu (`email` rỗng/blank, `password` rỗng `""`, hoặc không phải chuỗi):
    - Status: `400 Bad Request` — `"error": "Vui lòng nhập cả email và mật khẩu."`
  - Sai định dạng email (sau khi `trim().toLowerCase()`):
    - Status: `400 Bad Request` — `"error": "Định dạng email không hợp lệ."`
  - Email chưa tồn tại trong DB hoặc mật khẩu sai (kể cả mật khẩu ở biên `7` ký tự, `33` ký tự, sai 1 ký tự):
    - Status: `401 Unauthorized` — `"error": "Email hoặc mật khẩu không chính xác."`
  - Kiểm tra phiên `GET /api/me` với token thiếu/rỗng:
    - Status: `401 Unauthorized` — `"error": "Không tìm thấy token xác thực."`
  - Kiểm tra phiên `GET /api/me` với token sai chữ ký / sai định dạng:
    - Status: `403 Forbidden` — `"error": "Token không hợp lệ hoặc đã hết hạn."`
- **DbUnit Checkpoint**: Xác nhận thao tác đăng nhập (thành công hay thất bại) đều bảo toàn tuyệt đối dữ liệu trong bảng `users`, `user_info`, `edit_history` và đối chiếu thông tin user trả về khớp với dòng trong `ITable actualUsers`.

### 4.2. FR-03: Xem & Chỉnh sửa Thông tin Người dùng (`GET /api/profile`, `PUT /api/profile`, `GET /api/users`) — Gợi ý tên lớp: `FR_Profile_Test.java`
- **Các biên BVA & Phân vùng EP trong `ProfileController.java`**:
  - Trường `name` (sau khi `.trim()`):
    - Hợp lệ: độ dài từ **`1` đến `255` ký tự** (`BVA Min = 1`, `Min + 1 = 2`, `Max - 1 = 254`, `Max = 255`).
    - Không hợp lệ — Rỗng / chỉ chứa khoảng trắng / thiếu `name` / không phải chuỗi: `400 Bad Request` — `"error": "Tên không được để trống."`
    - Không hợp lệ — Độ dài `> 255` (`BVA Max + 1 = 256` ký tự): `400 Bad Request` — `"error": "Tên tối đa 255 ký tự."`
  - Trường `phoneNumber` (tùy chọn, regex `^[0-9+()\-\.\s]{6,30}$`):
    - Hợp lệ: `null`, chuỗi rỗng `""` (lưu vào DB là `NULL`), hoặc chuỗi hợp lệ dài từ **`6` đến `30` ký tự** (`BVA Min = 6`, `Min + 1 = 7`, `Max - 1 = 29`, `Max = 30`).
    - Không hợp lệ — Không phải chuỗi (số/boolean/mảng): `400 Bad Request` — `"error": "Số điện thoại không hợp lệ."`
    - Không hợp lệ — Độ dài `< 6` (`BVA Min - 1 = 5` ký tự), `> 30` (`BVA Max + 1 = 31` ký tự) hoặc chứa chữ cái/ký tự lạ (`abc`, `@`): `400 Bad Request` — `"error": "Số điện thoại chỉ gồm chữ số, khoảng trắng và + ( ) - . (6-30 ký tự)."`
  - Trường `role` (chỉ Admin được đổi):
    - Hợp lệ: `"user"` hoặc `"admin"`.
    - Không hợp lệ — Không phải chuỗi: `400 Bad Request` — `"error": "Vai trò không hợp lệ."`
    - Không hợp lệ — Giá trị khác `"user"`/`"admin"`: `400 Bad Request` — `"error": "Vai trò phải là 'user' hoặc 'admin'."`
    - Phân quyền RBAC:
      - User thường truyền `userId` của người khác khi `GET /api/profile` hoặc `PUT /api/profile`: `403 Forbidden` — `"error": "Bạn không có quyền truy cập hồ sơ của người dùng khác."`
      - User thường tự đổi `role` của chính mình thành `"admin"`: `403 Forbidden` — `"error": "Chỉ admin mới được thay đổi vai trò."`
      - Admin tự đổi `role` của chính mình thành `"user"`: `400 Bad Request` — `"error": "Admin không thể tự đổi vai trò của mình."`
      - User thường gọi `GET /api/users`: `403 Forbidden` — `"error": "Chỉ admin mới được tìm kiếm người dùng."`
  - Khi cập nhật thành công (`200 OK`):
    - Nếu có thay đổi: `"message": "Cập nhật thông tin thành công!"` $\rightarrow$ DbUnit xác nhận bảng `user_info` cập nhật giá trị mới và bảng `edit_history` tăng thêm **1 dòng** với cột JSONB `changed_fields` lưu đúng `before`/`after`.
    - Nếu dữ liệu gửi lên giống hệt dữ liệu hiện tại (No-op): `"message": "Không có thay đổi nào."` $\rightarrow$ DbUnit xác nhận bảng `edit_history` **không** sinh thêm bản ghi mới.

### 4.3. FR-04: Xem Lịch sử Chỉnh sửa (`GET /api/history`) — Gợi ý tên lớp: `FR_History_Test.java`
- **Quy tắc & Biên BVA trong `ProfileController.java`**:
  - Tự động ghi nhận lịch sử khi `PUT /api/profile` thay đổi `name`, `phone_number`, hoặc `role`.
  - Cột `actor_name` trong bảng `edit_history` chụp tên của người thực hiện tại thời điểm sửa và **bất biến** ngay cả khi người đó đổi tên sau này.
  - Phân quyền xem & lọc:
    - Role `user`: Chỉ xem được lịch sử có `target_user_id = me.id()` (nếu truyền `targetId` của người khác trên query param thì server tự động bỏ qua và ép về `me.id()`). Có thể lọc theo `actorId`.
    - Role `admin`: Xem được toàn bộ lịch sử của mọi người dùng, lọc được theo `actorId`, `targetId`, hoặc kết hợp cả hai.
  - Phân trang (`limit` & `offset`):
    - Mặc định `limit = 50`, giới hạn trong đoạn `[1, 100]` (`limit < 1` được ép về `1`, `limit > 100` được ép về `100`), `offset >= 0` (`offset < 0` được ép về `0`).
    - Cờ `hasMore = true` khi còn bản ghi cũ hơn ngoài `limit`.
