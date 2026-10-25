# Hệ Thống Xác Thực Người Dùng (Authentication System)

Tài liệu này mô tả cách thiết lập ứng dụng xác thực người dùng và các hành vi đăng ký, đăng nhập hiện được triển khai. Các kiểm tra định dạng email sử dụng mẫu phổ biến, không phải bộ xác thực đầy đủ cho toàn bộ RFC 5322.

---

## 1. Hướng Dẫn Thiết Lập (Setup Guide)

### 1.1. Yêu cầu môi trường (Prerequisites)
- **Java JDK** (phiên bản 8 trở lên) & **Maven**
- **Node.js** (phiên bản 18 trở lên) & **npm** (dành cho frontend)
- **Docker Desktop** (đang chạy, dùng để khởi tạo cơ sở dữ liệu PostgreSQL)

### 1.2. Cấu hình biến môi trường
Tạo các tệp `.env` trong thư mục gốc và `backend/`. Các giá trị bên dưới chỉ phù hợp để chạy thử trên máy cá nhân; hãy thay mật khẩu và khóa bí mật trước khi triển khai ở môi trường dùng chung hoặc production. Backend Java sẽ đọc tệp `backend/.env` khi được khởi chạy từ thư mục `backend/`.

* **Tệp `Demo/.env`** (cấu hình dịch vụ Docker PostgreSQL):
  ```env
  POSTGRES_USER=postgres
  POSTGRES_PASSWORD=postgres
  POSTGRES_DB=mydb
  POSTGRES_PORT=5432
  ```

* **Tệp `backend/.env`** (cấu hình máy chủ REST API & kết nối Database):
  ```env
  PORT=5000
  JWT_SECRET=replace_with_a_random_secret
  DATABASE_URL=postgresql://postgres:postgres@localhost:5432/mydb
  ```
  Thay `JWT_SECRET` bằng chuỗi ngẫu nhiên đủ mạnh, ví dụ tạo bằng `openssl rand -hex 32`. Backend sẽ không khởi động nếu thiếu `JWT_SECRET`. Nếu thiết lập đồng thời `DATABASE_URL` và các biến `DB_*`, `DATABASE_URL` được ưu tiên.

---

### 1.3. Các bước khởi chạy hệ thống

#### Bước 1: Khởi động cơ sở dữ liệu PostgreSQL
Mở terminal tại thư mục gốc `Demo/` và chạy lệnh:
```bash
docker compose up -d
```
> Container PostgreSQL 16 sẽ được khởi tạo với volume `postgres_data`, giúp dữ liệu tiếp tục tồn tại sau khi container dừng. Dữ liệu vẫn phụ thuộc vào việc volume không bị xóa.

#### Bước 2: Cài đặt và khởi chạy Backend API
Mở một cửa sổ terminal mới:
```bash
cd backend
mvn spring-boot:run
```
> Backend chỉ bắt đầu lắng nghe tại `http://localhost:5000` sau khi kết nối PostgreSQL và tạo bảng `users` thành công. Nếu kết nối hoặc khởi tạo bảng thất bại, tiến trình sẽ báo lỗi và không khởi động API. Hãy khởi động PostgreSQL trước và kiểm tra cấu hình kết nối nếu gặp lỗi.

#### Bước 3: Cài đặt và khởi chạy Frontend
Mở một cửa sổ terminal khác:
```bash
cd frontend
npm install
npm run dev
```
> Ứng dụng web sẽ chạy tại `http://localhost:5173`. Mở trình duyệt để truy cập và sử dụng hệ thống.

### 1.4. Quản lý mã nguồn trên Git
Thư mục `frontend/node_modules/` và các thư mục `dist/` được bỏ qua trong `.gitignore`, vì vậy Git sẽ không đưa các gói đã cài và tệp build sinh ra vào commit/push. Sau khi clone dự án, cài frontend và chạy backend Java bằng:

```bash
cd frontend
npm ci

cd ../backend
mvn spring-boot:run
```

Thư mục `frontend/dist/` sẽ được tạo lại khi chạy `npm run build` trong `frontend/`. Hãy commit `frontend/package.json` và `frontend/package-lock.json`; không xóa tệp lock vì chúng giúp cài lại phiên bản dependency nhất quán. Maven sẽ tải dependency backend theo `backend/pom.xml`. Các tệp `.env` cũng bị bỏ qua; cần tạo chúng theo mục **1.2. Cấu hình biến môi trường** trên từng máy.

---

## 2. Các Quy Tắc Yêu Cầu Chức Năng (Functional Requirements)

### FR-01: Chức Năng Đăng Ký (User Registration)

#### 1. Các trường thông tin đầu vào:
* **Email**: Địa chỉ email của tài khoản.
* **Mật khẩu (Password)**: Mật khẩu đăng nhập.
* **Xác nhận mật khẩu (Confirm Password)**: Nhập lại mật khẩu để kiểm tra độ chính xác.

#### 2. Các quy tắc xác thực (Validation Rules):
* **Kiểm tra trường bắt buộc**: Không được để trống bất kỳ trường nào trong 3 trường trên. API cũng từ chối các giá trị đầu vào không phải chuỗi.
* **Quy tắc Email**:
  * Phải phù hợp với mẫu email phổ biến mà ứng dụng kiểm tra (ví dụ: `name@example.com`). Mẫu này không bao quát mọi địa chỉ hợp lệ theo RFC 5322.
  * Email được loại bỏ khoảng trắng ở hai đầu và chuyển thành chữ thường trước khi lưu, đăng nhập và kiểm tra tính duy nhất.
  * **Tính duy nhất (Unique)**: Cơ sở dữ liệu áp dụng ràng buộc duy nhất. Email đã được đăng ký sẽ bị từ chối.
* **Quy tắc Mật khẩu (Password)**:
  * **Độ dài tối thiểu**: Mật khẩu phải có độ dài từ **8 ký tự trở lên**.
  * **Giới hạn số ký tự (Độ dài tối đa)**: Mật khẩu bị giới hạn, tối đa **32 ký tự**.
  * **Chữ viết hoa**: Bắt buộc phải chứa ít nhất **1 chữ cái in hoa** (`A-Z`).
  * **Số**: Bắt buộc phải chứa ít nhất **1 chữ số** (`0-9`).
  * **Ký tự đặc biệt**: Bắt buộc phải chứa ít nhất **1 ký tự dấu câu hoặc ký hiệu** (ví dụ: `!@#$%^&*...`). Chữ cái Unicode có dấu tự nó không được tính là ký tự đặc biệt.
  * *Hỗ trợ trực quan trên Frontend*: Danh sách tiêu chí được cập nhật khi nhập mật khẩu; tiêu chí đạt yêu cầu đổi màu xanh và hiển thị dấu `✓`.
* **Quy tắc Xác nhận mật khẩu**:
  * Giá trị tại ô xác nhận mật khẩu phải trùng khớp hoàn toàn với mật khẩu đã nhập.

#### 3. Quy tắc xử lý và bảo mật:
* Mật khẩu được băm bằng thư viện `bcryptjs` (salt rounds = 10) trước khi lưu; ứng dụng không lưu mật khẩu dạng văn bản thuần.
* Khi đăng ký thành công:
  * Tạo bản ghi người dùng mới trong bảng `users` của PostgreSQL.
  * Trả về HTTP status `201 Created`.
  * Chuyển người dùng sang tab Đăng nhập và điền sẵn email vừa đăng ký.

---

### FR-02: Chức Năng Đăng Nhập (User Login)

#### 1. Các trường thông tin đầu vào:
* **Email**: Địa chỉ email của tài khoản.
* **Mật khẩu (Password)**: Mật khẩu tương ứng của tài khoản.

#### 2. Các quy tắc xác thực (Validation Rules):
* **Kiểm tra trường bắt buộc**: Phải điền đầy đủ cả 2 trường Email và Mật khẩu. API cũng từ chối các giá trị đầu vào không phải chuỗi.
* **Quy tắc Email**: Kiểm tra theo mẫu email phổ biến trước khi gửi yêu cầu; mẫu này không bao quát mọi địa chỉ hợp lệ theo RFC 5322.

#### 3. Quy tắc đối chiếu và quản lý phiên (Authentication & Session):
* **Xác thực tài khoản**:
  * Tìm kiếm tài khoản theo email đã chuẩn hóa. Nếu không tìm thấy hoặc mật khẩu không khớp (so sánh bằng `bcrypt.compare`), hệ thống từ chối đăng nhập và trả về thông báo lỗi chung.
* **Cấp phát token xác thực**:
  * Khi đăng nhập thành công, máy chủ cấp JWT (JSON Web Token) có thời hạn 24 giờ.
  * Frontend lưu token trong `localStorage` với khóa `auth_token` và gọi API để khôi phục phiên khi tải lại trang. `localStorage` có thể bị đọc bởi JavaScript chạy cùng origin; đây là lựa chọn lưu trữ của ứng dụng mẫu, không nên coi là an toàn tuyệt đối hoặc dùng thay cho đánh giá bảo mật.
* **Điều hướng sau khi đăng nhập**:
  * Tự động chuyển người dùng đến màn hình **"Hello"**.
  * Hiển thị lời chào cá nhân hóa theo email: `Hello, <email>!`, cùng mã tài khoản (ID) và ngày tạo tài khoản.
  * Cung cấp nút **Đăng xuất (Logout)** để xóa token khỏi `localStorage` ở trình duyệt hiện tại và quay về màn hình Đăng nhập. Thao tác này không thu hồi JWT đã phát hành ở phía máy chủ; token còn hiệu lực đến khi hết hạn (24 giờ).

---

### FR-03: Xem và Chỉnh Sửa Thông Tin Người Dùng (User Information Management)

#### 1. Thông tin người dùng:
* Thông tin hồ sơ gồm **Tên**, **Số điện thoại** và **Vai trò**; tài khoản và hồ sơ được liên kết bằng ID người dùng.

#### 2. Quyền xem và chỉnh sửa:
* **Role `user`**: Chỉ được xem và chỉnh sửa thông tin hồ sơ của chính mình.
* **Role `admin`**: Có ô nhập tên người dùng để tìm/chọn người dùng khác, sau đó có thể xem và chỉnh sửa thông tin hồ sơ của người đó.
* Máy chủ phải xác thực quyền trên mỗi yêu cầu; không chỉ dựa vào giao diện để ngăn người dùng sửa hồ sơ của người khác.

---

### FR-04: Xem Lịch Sử Chỉnh Sửa Thông Tin (Edit History)

#### 1. Nội dung lịch sử:
* Mỗi lần thông tin người dùng được chỉnh sửa thành công, hệ thống ghi một bản ghi lịch sử gồm **người thực hiện**, **người được chỉnh sửa**, **thời điểm chỉnh sửa** và các trường đã thay đổi cùng giá trị trước/sau.
* Người thực hiện phải được xác định từ phiên đăng nhập đã xác thực ở phía máy chủ, không lấy từ dữ liệu do trình duyệt tự khai báo.
* Lịch sử chỉ được bổ sung; ứng dụng không cho phép người dùng thông thường sửa hoặc xóa các bản ghi lịch sử.

#### 2. Quyền xem và lọc:
* **Role `user`**: Chỉ được xem lịch sử của chính mình, bao gồm cả các thay đổi do admin thực hiện cho tài khoản đó; lịch sử hiển thị tên người thực hiện.
* **Role `admin`**: Được xem lịch sử của tất cả người dùng và có thể lọc theo **người thực hiện** và/hoặc **người được chỉnh sửa**.
* Máy chủ phải áp dụng quyền và điều kiện lọc trên mỗi yêu cầu; không chỉ dựa vào giao diện để giới hạn dữ liệu.

---

## 3. Mô Hình Cơ Sở Dữ Liệu (SQL Relationships)

### 3.1. Khai báo bảng

```sql
CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE roles (
    role_id SERIAL PRIMARY KEY,
    role_name VARCHAR(50) UNIQUE NOT NULL
);

CREATE TABLE user_info (
    id INTEGER PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    phone_number VARCHAR(30),
    user_role INTEGER NOT NULL
);

CREATE TABLE edit_history (
    history_id SERIAL PRIMARY KEY,
    actor_user_id INTEGER NOT NULL,
    target_user_id INTEGER NOT NULL,
    changed_fields JSONB NOT NULL,
    edited_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

### 3.2. Khai báo khóa ngoại

```sql
ALTER TABLE user_info
    ADD CONSTRAINT fk_user_info_user
    FOREIGN KEY (id) REFERENCES users(id);

ALTER TABLE user_info
    ADD CONSTRAINT fk_user_info_role
    FOREIGN KEY (user_role) REFERENCES roles(role_id);

ALTER TABLE edit_history
    ADD CONSTRAINT fk_edit_history_actor
    FOREIGN KEY (actor_user_id) REFERENCES users(id) ON DELETE RESTRICT;

ALTER TABLE edit_history
    ADD CONSTRAINT fk_edit_history_target
    FOREIGN KEY (target_user_id) REFERENCES users(id) ON DELETE RESTRICT;
```

### 3.3. Chỉ mục phục vụ truy vấn lịch sử

```sql
CREATE INDEX idx_edit_history_actor_time
    ON edit_history (actor_user_id, edited_at DESC);

CREATE INDEX idx_edit_history_target_time
    ON edit_history (target_user_id, edited_at DESC);
```

`user_info.id` vừa là khóa chính của hồ sơ vừa tham chiếu đến `users.id`, bảo đảm mỗi tài khoản có tối đa một hồ sơ. `user_info.user_role` lưu `roles.role_id`; bảng `roles` chứa các vai trò như `user` và `admin`. Trong `edit_history`, `actor_user_id` là người thực hiện thay đổi, còn `target_user_id` là người có hồ sơ bị thay đổi; hai khóa ngoại đều tham chiếu `users.id`. `changed_fields` là JSONB lưu các trường được sửa cùng giá trị trước/sau. Hai chỉ mục hỗ trợ lấy lịch sử theo người được chỉnh sửa (quyền user) và lọc theo người thực hiện (quyền admin). `ON DELETE RESTRICT` giữ nguyên khả năng truy vết và ngăn xóa tài khoản còn được lịch sử tham chiếu.
