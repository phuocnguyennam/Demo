# Hệ Thống Xác Thực Người Dùng (Authentication System)

Tài liệu này mô tả cách thiết lập ứng dụng xác thực người dùng và các hành vi đăng ký, đăng nhập hiện được triển khai. Các kiểm tra định dạng email sử dụng mẫu phổ biến, không phải bộ xác thực đầy đủ cho toàn bộ RFC 5322.

---

## 1. Hướng Dẫn Thiết Lập (Setup Guide)

### 1.1. Yêu cầu môi trường (Prerequisites)
- **Node.js** (phiên bản 18 trở lên) & **npm**
- **Docker Desktop** (đang chạy, dùng để khởi tạo cơ sở dữ liệu PostgreSQL)

### 1.2. Cấu hình biến môi trường
Tạo các tệp `.env` trong thư mục gốc và `backend/`. Các giá trị bên dưới chỉ phù hợp để chạy thử trên máy cá nhân; hãy thay mật khẩu và khóa bí mật trước khi triển khai ở môi trường dùng chung hoặc production.

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
  Thay `JWT_SECRET` bằng chuỗi ngẫu nhiên đủ mạnh, ví dụ tạo bằng `node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"`. Backend sẽ không khởi động nếu thiếu `JWT_SECRET`. Nếu thiết lập đồng thời `DATABASE_URL` và các biến `DB_*`, `DATABASE_URL` được ưu tiên.

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
npm install
npm run dev
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
Các thư mục `node_modules/` và `dist/` được bỏ qua trong `.gitignore`, vì vậy Git sẽ không đưa các gói đã cài và tệp build sinh ra vào commit/push. Sau khi clone dự án, cài dependency từ các tệp lock bằng:

```bash
cd backend
npm ci

cd ../frontend
npm ci
```

Thư mục `frontend/dist/` sẽ được tạo lại khi chạy `npm run build` trong `frontend/`. Hãy commit `package.json` và `package-lock.json` của mỗi ứng dụng; không xóa tệp lock, vì chúng giúp cài lại phiên bản dependency nhất quán. Các tệp `.env` cũng bị bỏ qua; cần tạo chúng theo mục **1.2. Cấu hình biến môi trường** trên từng máy.

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
