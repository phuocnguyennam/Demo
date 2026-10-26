package com.demo.auth;

import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Xác thực JWT trên mỗi yêu cầu và xác định vai trò hiện tại từ cơ sở dữ liệu. */
@Service
public class AuthService {
    public static class Principal {
        private final long id;
        private final String role;

        Principal(long id, String role) {
            this.id = id;
            this.role = role;
        }

        public long getId() {
            return id;
        }

        public String getRole() {
            return role;
        }

        public boolean isAdmin() {
            return "admin".equals(role);
        }
    }

    private final JwtService jwtService;
    private final JdbcTemplate jdbcTemplate;

    public AuthService(JwtService jwtService, JdbcTemplate jdbcTemplate) {
        this.jwtService = jwtService;
        this.jdbcTemplate = jdbcTemplate;
    }

    public Principal authenticate(String authorization) {
        String token = null;
        if (authorization != null) {
            String[] parts = authorization.split(" ", -1);
            token = parts.length > 1 ? parts[1] : null;
        }
        if (token == null || token.isEmpty()) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "Không tìm thấy token xác thực.");
        }

        final DecodedJWT decoded;
        try {
            decoded = jwtService.verify(token);
        } catch (JWTVerificationException error) {
            throw new AuthException(HttpStatus.FORBIDDEN, "Token không hợp lệ hoặc đã hết hạn.");
        }
        Long userId = decoded.getClaim("id").asLong();
        if (userId == null) {
            throw new AuthException(HttpStatus.FORBIDDEN, "Token không hợp lệ hoặc đã hết hạn.");
        }

        // Vai trò luôn được đọc từ DB, không tin dữ liệu do client gửi lên.
        List<String> roles = jdbcTemplate.queryForList(
                "SELECT r.role_name FROM users u " +
                "LEFT JOIN user_info ui ON ui.id = u.id " +
                "LEFT JOIN roles r ON r.role_id = ui.user_role " +
                "WHERE u.id = ?", String.class, userId);
        if (roles.isEmpty()) {
            throw new AuthException(HttpStatus.NOT_FOUND, "Người dùng không tồn tại.");
        }
        String role = roles.get(0) == null ? "user" : roles.get(0);
        return new Principal(userId, role);
    }
}
