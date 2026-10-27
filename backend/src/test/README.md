# Hướng Dẫn Thực Thi Kiểm Thử & Luồng Kiểm Thử Tự Động (Backend Test Suite)

Tài liệu này hướng dẫn cách chạy các bộ kiểm thử tự động trong thư mục `backend/src/test` và giải thích chi tiết **luồng thực thi kiểm thử tích hợp** kết hợp **Datafaker + Spring Boot (`TestRestTemplate`) + Testcontainers (PostgreSQL) + DbUnit**.

---

## 1. Cấu Trúc Thư Mục Test

```text
backend/src/test/
├── README.md                                        # Tài liệu hướng dẫn thực thi & luồng kiểm thử
├── java/com/demo/auth/
│   ├── RegistrationFakerDbUnitTest.java             # Kiểm thử FR-01 (EP & BVA: TC01 -> TC17) với Datafaker + DbUnit
│   ├── AuthenticationFlowIntegrationTest.java       # Kiểm thử luồng E2E từ FR-01 đến FR-04
│   └── PostgreSqlContainerSmokeTest.java            # Smoke test kiểm tra kết nối container PostgreSQL
└── resources/datasets/
    ├── expected-register-success.xml                # Mẫu DbUnit XML động khi đăng ký thành công (201 Created)
    └── expected-register-unchanged.xml              # Mẫu DbUnit XML khi đăng ký thất bại (400 Bad Request)
```

---

## 2. Hướng Dẫn Thực Thi Test

### 2.1. Yêu cầu trước khi chạy
1. **Java JDK 27+** và **Maven** đã được cài đặt trong `PATH`.
2. **Docker Desktop** phải đang mở và ở trạng thái hoạt động (`Running`).
   > *Lưu ý:* Bạn **không** cần chạy `docker compose up` thủ công. **Testcontainers** sẽ tự động khởi tạo một container `postgres:16-alpine` riêng biệt cho quá trình test và tự động xóa container đó sau khi chạy xong.

### 2.2. Các lệnh chạy kiểm thử (chạy từ thư mục gốc `Demo/`)

* **Chạy riêng bộ kiểm thử Đăng ký với Datafaker & DbUnit (`TC01` → `TC17`):**
  ```powershell
  mvn -f backend\pom.xml -Dtest=RegistrationFakerDbUnitTest test
  ```

* **Chạy toàn bộ các lớp kiểm thử trong dự án:**
  ```powershell
  mvn -f backend\pom.xml test
  ```

* **Chạy riêng kiểm thử luồng tích hợp FR-01 → FR-04:**
  ```powershell
  mvn -f backend\pom.xml -Dtest=AuthenticationFlowIntegrationTest test
  ```

### 2.3. Xem báo cáo dữ liệu Faker và bảng Database sau khi chạy test
Vì Testcontainers tự động dọn dẹp container PostgreSQL ngay khi test kết thúc, lớp `RegistrationFakerDbUnitTest` đã tự động chụp (snapshot) và xuất toàn bộ dữ liệu của 17 kịch bản (`TC01` → `TC17`) ra 2 nơi:
1. **Console Log** trên màn hình terminal khi chạy lệnh Maven.
2. **Tệp báo cáo UTF-8 cố định** tại:
   ```text
   backend/target/faker-dbunit-report.log
   ```
   Mở tệp này để xem chi tiết từng kịch bản gồm:
   - `[1] DỮ LIỆU FAKER TẠO RA (REQUEST PAYLOAD)`
   - `[2] KẾT QUẢ PHẢN HỒI TỪ BACKEND (HTTP RESPONSE)`
   - `[3] BẢNG MONG ĐỢI ĐỘNG DBUNIT (EXPECTED DATASET)`
   - `[4] BẢNG THỰC TẾ TRONG POSTGRESQL (ACTUAL DATABASE)`

---

## 3. Luồng Thực Thi Kiểm Thử (`RegistrationFakerDbUnitTest`)
### Giải thích chi tiết 6 bước trong luồng thực thi

#### Bước 1: Khởi tạo hạ tầng cô lập với Testcontainers
* Khi lớp test bắt đầu, `@Container PostgreSQLContainer<?>` dựng một container `postgres:16-alpine`.
* `@DynamicPropertySource` truyền động chuỗi kết nối `DATABASE_URL`, `DB_USER`, `DB_PASSWORD`, `JWT_SECRET`, `ADMIN_EMAIL` vào Spring Context.
* `DatabaseInitializer` tự động chạy để khởi tạo 4 bảng (`users`, `roles`, `user_info`, `edit_history`), nạp 2 vai trò (`user` với `role_id = 1`, `admin` với `role_id = 2`) và tạo sẵn tài khoản Admin đầu tiên (`id = 1`, `email = admin@example.com`).

#### Bước 2: Thiết lập trạng thái sạch trước mỗi kịch bản (`@BeforeEach`)
* Để các test case hoàn toàn độc lập với nhau, hàm `setUpDbUnit()` chạy trước mỗi kịch bản sẽ xóa các bản ghi phát sinh từ test trước (`DELETE FROM user_info WHERE id > 1; DELETE FROM users WHERE id > 1;`), đưa DB về đúng 1 dòng gốc (`id = 1` của Admin).
* Mở kết nối `IDatabaseConnection` của **DbUnit** với cấu hình `PostgresqlDataTypeFactory`.

#### Bước 3: Sinh dữ liệu đầu vào động bằng Datafaker (Áp dụng EP & BVA)
* Thay vì gõ cứng dữ liệu tĩnh, **Datafaker** (`net.datafaker.Faker`) dùng `faker.internet().emailAddress()` và `faker.regexify(...)` để sinh dữ liệu ngẫu nhiên nhưng vẫn tuân thủ chặt chẽ từng phân vùng tương đương (**EP**) và giá trị biên (**BVA**):
  * Biên độ dài mật khẩu: `7` (`Min - 1`), `8` (`Min`), `9` (`Min + 1`), `31` (`Max - 1`), `32` (`Max`), `33` (`Max + 1`).
  * Các phân vùng thiếu chữ hoa, thiếu chữ số, thiếu ký tự đặc biệt, dùng chữ Unicode có dấu (`ăêôđ`), sai định dạng email, trùng email, lệch mật khẩu xác nhận, rỗng/khoảng trắng, sai kiểu dữ liệu.

#### Bước 4: Gửi Request đến Backend API & Kiểm tra HTTP Response
* Sử dụng `TestRestTemplate` gửi `POST /api/register` tới cổng ngẫu nhiên (`@LocalServerPort`) của máy chủ Spring Boot nhúng.
* Kiểm tra mã trạng thái HTTP (`201 Created`, `400 Bad Request` hoặc `409 Conflict`) và thông điệp phản hồi.

#### Bước 5: Tạo Bảng Kết Quả Mong Đợi Động (Dynamic Expected Dataset) trong DbUnit
* Vì `email`, `id`, `name` được sinh động ở Bước 3 & 4, DbUnit sử dụng `ReplacementDataSet` nạp tệp mẫu XML (`expected-register-success.xml`) và thay thế các placeholder ngay tại runtime:
  * `[EXPECTED_ID]` $\leftarrow$ `response.body.user.id`
  * `[EXPECTED_EMAIL]` $\leftarrow$ `rawEmail.trim().toLowerCase()`
  * `[EXPECTED_NAME]` $\leftarrow$ phần đứng trước dấu `@` của `expectedEmail`
  * `[EXPECTED_ROLE_ID]` $\leftarrow$ `1` (vai trò `user`)
  * `[NULL]` $\leftarrow$ `null` (cho cột `phone_number`)

#### Bước 6: Ghi Log Báo Cáo & Đối Chiếu Trạng Thái Database bằng DbUnit
* **Ghi log (`logTestSnapshot`)**: Trích xuất dữ liệu từ `ITable` của DbUnit và vẽ bảng ASCII ra console cùng tệp `backend/target/faker-dbunit-report.log`.
* **Xử lý các cột không tiền định (`password_hash`, `created_at`)**:
  * Dùng `DefaultColumnFilter.excludedColumnsTable(actualUsersTable, new String[]{"password_hash", "created_at"})` để loại 2 cột sinh ngẫu nhiên khỏi phép so sánh XML của DbUnit.
  * Kiểm tra riêng cột `password_hash` bằng `BCryptPasswordEncoder.matches(rawPassword, actualPasswordHash)` và kiểm tra `created_at != null`.
* **So sánh toàn vẹn bảng**: Gọi `Assertion.assertEquals(expectedTable, actualTable)` trên cả 2 bảng `users` và `user_info`.

---

## 4. Danh Sách 17 Kịch Bản Kiểm Thử (`TC01` → `TC17`)

Các kịch bản được cố định thứ tự thực thi bằng `@TestMethodOrder(MethodOrderer.OrderAnnotation.class)`:

| Thứ tự | Mã TC | Kỹ thuật | Mô tả kịch bản | HTTP Status | Số dòng trong DB (`users` & `user_info`) |
| :---: | :--- | :--- | :--- | :---: | :--- |
| 1 | **TC01** | EP Valid | Đăng ký hợp lệ tiêu chuẩn (mật khẩu 12 ký tự) | `201` | 2 dòng (Admin + User mới) |
| 2 | **TC02** | EP Valid | Email có khoảng trắng 2 đầu và chữ in HOA | `201` | 2 dòng (Email được `trim` + `lowercase`) |
| 3 | **TC03** | BVA Valid | Mật khẩu tại biên dưới `Min = 8` ký tự | `201` | 2 dòng (Admin + User mới) |
| 4 | **TC04** | BVA Valid | Mật khẩu ngay trên biên dưới `Min + 1 = 9` ký tự | `201` | 2 dòng (Admin + User mới) |
| 5 | **TC05** | BVA Valid | Mật khẩu ngay dưới biên trên `Max - 1 = 31` ký tự | `201` | 2 dòng (Admin + User mới) |
| 6 | **TC06** | BVA Valid | Mật khẩu tại biên trên `Max = 32` ký tự | `201` | 2 dòng (Admin + User mới) |
| 7 | **TC07** | BVA Invalid | Mật khẩu dưới biên tối thiểu `Min - 1 = 7` ký tự | `400` | 1 dòng (Chỉ có Admin, DB không đổi) |
| 8 | **TC08** | BVA Invalid | Mật khẩu vượt biên tối đa `Max + 1 = 33` ký tự | `400` | 1 dòng (Chỉ có Admin, DB không đổi) |
| 9 | **TC09** | EP Invalid | Mật khẩu thiếu chữ cái in hoa (`A-Z`) | `400` | 1 dòng (Chỉ có Admin, DB không đổi) |
| 10 | **TC10** | EP Invalid | Mật khẩu thiếu chữ số (`0-9`) | `400` | 1 dòng (Chỉ có Admin, DB không đổi) |
| 11 | **TC11** | EP Invalid | Mật khẩu thiếu ký tự đặc biệt | `400` | 1 dòng (Chỉ có Admin, DB không đổi) |
| 12 | **TC12** | EP Invalid | Mật khẩu dùng chữ Unicode có dấu (`ăêôđ`) thay ký tự đặc biệt | `400` | 1 dòng (Chỉ có Admin, DB không đổi) |
| 13 | **TC13** | EP Invalid | Email sai định dạng (`word@invalid`) | `400` | 1 dòng (Chỉ có Admin, DB không đổi) |
| 14 | **TC14** | EP Invalid | Đăng ký trùng Email đã tồn tại (khác hoa/thường) | `409` | 2 dòng (Giữ nguyên bản ghi lần 1, không tạo dòng thứ 3) |
| 15 | **TC15** | EP Invalid | Xác nhận mật khẩu (`confirmPassword`) không khớp | `400` | 1 dòng (Chỉ có Admin, DB không đổi) |
| 16 | **TC16** | EP Invalid | Trường bắt buộc để trống / chỉ chứa khoảng trắng | `400` | 1 dòng (Chỉ có Admin, DB không đổi) |
| 17 | **TC17** | EP Invalid | Giá trị đầu vào không phải chuỗi (số, mảng, boolean) | `400` | 1 dòng (Chỉ có Admin, DB không đổi) |
