package com.demo.auth;

import java.util.Locale;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DatabaseInitializer implements InitializingBean {
    private final JdbcTemplate jdbcTemplate;
    private final String adminEmail;
    private final String adminPassword;

    public DatabaseInitializer(JdbcTemplate jdbcTemplate,
            @Value("${ADMIN_EMAIL:admin@example.com}") String adminEmail,
            @Value("${ADMIN_PASSWORD:Admin@123456}") String adminPassword) {
        this.jdbcTemplate = jdbcTemplate;
        this.adminEmail = adminEmail.trim().toLowerCase(Locale.ROOT);
        this.adminPassword = adminPassword;
    }

    @Override
    public void afterPropertiesSet() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    id SERIAL PRIMARY KEY,
                    email VARCHAR(255) UNIQUE NOT NULL,
                    password_hash VARCHAR(255) NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS roles (
                    role_id SERIAL PRIMARY KEY,
                    role_name VARCHAR(50) UNIQUE NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                INSERT INTO roles (role_name) VALUES ('user'), ('admin')
                ON CONFLICT (role_name) DO NOTHING
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS user_info (
                    id INTEGER PRIMARY KEY,
                    name VARCHAR(255) NOT NULL,
                    phone_number VARCHAR(30),
                    user_role INTEGER NOT NULL
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS edit_history (
                    history_id SERIAL PRIMARY KEY,
                    actor_user_id INTEGER NOT NULL,
                    target_user_id INTEGER NOT NULL,
                    changed_fields JSONB NOT NULL,
                    edited_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);

        // Tên người thực hiện được chụp tại thời điểm chỉnh sửa (bản ghi cũ có thể null).
        jdbcTemplate.execute("ALTER TABLE edit_history ADD COLUMN IF NOT EXISTS actor_name VARCHAR(255)");

        addForeignKey("user_info", "fk_user_info_user",
                "FOREIGN KEY (id) REFERENCES users(id)");
        addForeignKey("user_info", "fk_user_info_role",
                "FOREIGN KEY (user_role) REFERENCES roles(role_id)");
        addForeignKey("edit_history", "fk_edit_history_actor",
                "FOREIGN KEY (actor_user_id) REFERENCES users(id) ON DELETE RESTRICT");
        addForeignKey("edit_history", "fk_edit_history_target",
                "FOREIGN KEY (target_user_id) REFERENCES users(id) ON DELETE RESTRICT");

        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_edit_history_actor_time
                ON edit_history (actor_user_id, edited_at DESC)
                """);
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_edit_history_target_time
                ON edit_history (target_user_id, edited_at DESC)
                """);

        // Tạo hồ sơ mặc định (role user) cho các tài khoản đã đăng ký trước khi có bảng user_info.
        jdbcTemplate.execute("""
                INSERT INTO user_info (id, name, user_role)
                SELECT u.id, split_part(u.email, '@', 1), r.role_id
                FROM users u CROSS JOIN roles r
                WHERE r.role_name = 'user'
                AND NOT EXISTS (SELECT 1 FROM user_info ui WHERE ui.id = u.id)
                """);

        seedFirstAdmin();
    }

    /**
     * Tạo tài khoản admin đầu tiên nếu trong DB chưa có admin nào. Các tài khoản khác chỉ trở thành
     * admin khi một admin sửa role của họ.
     */
    private void seedFirstAdmin() {
        Integer admins = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM user_info ui JOIN roles r ON r.role_id = ui.user_role
                WHERE r.role_name = 'admin'
                """, Integer.class);
        if (admins != null && admins > 0) {
            return;
        }
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, adminEmail);
        if (existing != null && existing > 0) {
            // Chưa có admin nào mà email admin mặc định đã tồn tại: dùng luôn tài khoản đó làm admin đầu tiên.
            jdbcTemplate.update("""
                    UPDATE user_info SET user_role = (SELECT role_id FROM roles WHERE role_name = 'admin')
                    WHERE id = (SELECT id FROM users WHERE email = ?)
                    """, adminEmail);
            return;
        }
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash) VALUES (?, ?) RETURNING id", Long.class,
                adminEmail, new BCryptPasswordEncoder(10).encode(adminPassword));
        jdbcTemplate.update("""
                INSERT INTO user_info (id, name, user_role)
                VALUES (?, 'Admin', (SELECT role_id FROM roles WHERE role_name = 'admin'))
                """, id);
    }

    private void addForeignKey(String table, String constraint, String definition) {
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_constraint WHERE conname = ?", Integer.class, constraint);
        if (existing == null || existing == 0) {
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD CONSTRAINT " + constraint + " " + definition);
        }
    }
}
