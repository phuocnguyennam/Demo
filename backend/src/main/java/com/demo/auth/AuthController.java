package com.demo.auth;

import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AuthController {
    private static final Pattern EMAIL_REGEX = Pattern.compile(
            "^[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+$");

    private final JdbcTemplate jdbcTemplate;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(10);
    private final JwtService jwtService;
    private final TransactionTemplate transactionTemplate;

    public AuthController(JdbcTemplate jdbcTemplate, JwtService jwtService,
            PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.jwtService = jwtService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "OK");
            response.put("database", "connected");
            return ResponseEntity.ok(response);
        } catch (DataAccessException error) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "ERROR");
            response.put("database", "disconnected");
            response.put("details", error.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody(required = false) JsonNode body) {
        String email = textField(body, "email");
        String password = textField(body, "password");
        String confirmPassword = textField(body, "confirmPassword");
        if (email == null || email.isBlank() || password == null || password.isEmpty()
                || confirmPassword == null || confirmPassword.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST,
                    "Vui lòng điền đầy đủ email, mật khẩu và xác nhận mật khẩu.");
        }

        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        if (!EMAIL_REGEX.matcher(normalizedEmail).matches()) {
            return error(HttpStatus.BAD_REQUEST,
                    "Định dạng email không hợp lệ (Ví dụ: user@example.com).");
        }

        String passwordError = validatePassword(password);
        if (passwordError != null) {
            return error(HttpStatus.BAD_REQUEST, passwordError);
        }
        if (!password.equals(confirmPassword)) {
            return error(HttpStatus.BAD_REQUEST,
                    "Mật khẩu xác nhận không khớp với mật khẩu đã nhập.");
        }

        try {
            Integer existingUserCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, normalizedEmail);
            if (existingUserCount != null && existingUserCount > 0) {
                return error(HttpStatus.CONFLICT,
                        "Email này đã được sử dụng. Vui lòng chọn một email khác.");
            }

            final String roleName = "user";
            final String passwordHash = passwordEncoder.encode(password);
            // Tạo tài khoản và hồ sơ mặc định trong cùng một giao dịch.
            Map<String, Object> savedUser = transactionTemplate.execute(status -> {
                Map<String, Object> inserted = jdbcTemplate.queryForMap("""
                        INSERT INTO users (email, password_hash) VALUES (?, ?)
                        RETURNING id, email, created_at
                        """, normalizedEmail, passwordHash);
                jdbcTemplate.update("""
                        INSERT INTO user_info (id, name, user_role)
                        VALUES (?, ?, (SELECT role_id FROM roles WHERE role_name = ?))
                        """,
                        inserted.get("id"), normalizedEmail.substring(0, normalizedEmail.indexOf('@')),
                        roleName);
                return inserted;
            });
            Map<String, Object> user = userResponse(savedUser);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("message", "Đăng ký tài khoản thành công!");
            response.put("user", user);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (DuplicateKeyException error) {
            return error(HttpStatus.CONFLICT,
                    "Email này đã được sử dụng. Vui lòng chọn một email khác.");
        } catch (DataAccessException error) {
            System.err.println("Lỗi khi đăng ký: " + error.getMessage());
            return error(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Đã có lỗi xảy ra ở máy chủ. Vui lòng thử lại sau.");
        }
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody(required = false) JsonNode body) {
        String email = textField(body, "email");
        String password = textField(body, "password");
        if (email == null || email.isBlank() || password == null || password.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "Vui lòng nhập cả email và mật khẩu.");
        }

        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        if (!EMAIL_REGEX.matcher(normalizedEmail).matches()) {
            return error(HttpStatus.BAD_REQUEST, "Định dạng email không hợp lệ.");
        }

        try {
            Map<String, Object> user = jdbcTemplate.queryForMap(
                    "SELECT id, email, password_hash, created_at FROM users WHERE email = ?",
                    normalizedEmail);
            String passwordHash = (String) user.get("password_hash");
            if (!passwordEncoder.matches(password, passwordHash)) {
                return error(HttpStatus.UNAUTHORIZED, "Email hoặc mật khẩu không chính xác.");
            }

            long userId = ((Number) user.get("id")).longValue();
            String userEmail = (String) user.get("email");
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("message", "Đăng nhập thành công!");
            response.put("token", jwtService.createToken(userId, userEmail));
            response.put("user", userResponse(user));
            return ResponseEntity.ok(response);
        } catch (EmptyResultDataAccessException error) {
            return error(HttpStatus.UNAUTHORIZED, "Email hoặc mật khẩu không chính xác.");
        } catch (DataAccessException error) {
            System.err.println("Lỗi khi đăng nhập: " + error.getMessage());
            return error(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Đã có lỗi xảy ra ở máy chủ. Vui lòng thử lại sau.");
        }
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> currentUser(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String token = authorization == null ? null : tokenFromHeader(authorization);
        if (token == null || token.isBlank()) {
            return error(HttpStatus.UNAUTHORIZED, "Không tìm thấy token xác thực.");
        }

        final DecodedJWT decoded;
        try {
            decoded = jwtService.verify(token);
        } catch (JWTVerificationException error) {
            return error(HttpStatus.FORBIDDEN, "Token không hợp lệ hoặc đã hết hạn.");
        }

        Long userId = decoded.getClaim("id").asLong();
        if (userId == null) {
            return error(HttpStatus.FORBIDDEN, "Token không hợp lệ hoặc đã hết hạn.");
        }
        try {
            Map<String, Object> user = jdbcTemplate.queryForMap(
                    "SELECT id, email, created_at FROM users WHERE id = ?", userId);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("user", userResponse(user));
            return ResponseEntity.ok(response);
        } catch (EmptyResultDataAccessException error) {
            return error(HttpStatus.NOT_FOUND, "Người dùng không tồn tại.");
        } catch (DataAccessException error) {
            System.err.println("Lỗi khi lấy thông tin người dùng: " + error.getMessage());
            return error(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Lỗi máy chủ khi lấy thông tin tài khoản.");
        }
    }

    private static String tokenFromHeader(String authorization) {
        String[] parts = authorization.split(" ", -1);
        return parts.length > 1 ? parts[1] : null;
    }

    private static String textField(JsonNode body, String field) {
        if (body == null || !body.isObject()) {
            return null;
        }
        JsonNode value = body.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static String validatePassword(String password) {
        if (password.length() < 8 || password.length() > 32) {
            return "Mật khẩu phải có độ dài từ 8 đến 32 ký tự.";
        }
        if (!Pattern.compile("[A-Z]").matcher(password).find()) {
            return "Mật khẩu phải chứa ít nhất 1 chữ cái in hoa (A-Z).";
        }
        if (!Pattern.compile("[0-9]").matcher(password).find()) {
            return "Mật khẩu phải chứa ít nhất 1 chữ số (0-9).";
        }
        if (!hasUnicodePunctuationOrSymbol(password)) {
            return "Mật khẩu phải chứa ít nhất 1 ký tự đặc biệt (ví dụ: !@#$%^&*...).";
        }
        return null;
    }

    private static boolean hasUnicodePunctuationOrSymbol(String value) {
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            boolean isPunctuationOrSymbol = switch (Character.getType(codePoint)) {
                case Character.CONNECTOR_PUNCTUATION,
                        Character.DASH_PUNCTUATION,
                        Character.START_PUNCTUATION,
                        Character.END_PUNCTUATION,
                        Character.INITIAL_QUOTE_PUNCTUATION,
                        Character.FINAL_QUOTE_PUNCTUATION,
                        Character.OTHER_PUNCTUATION,
                        Character.MATH_SYMBOL,
                        Character.CURRENCY_SYMBOL,
                        Character.MODIFIER_SYMBOL,
                        Character.OTHER_SYMBOL -> true;
                default -> false;
            };
            if (isPunctuationOrSymbol) {
                return true;
            }
            offset += Character.charCount(codePoint);
        }
        return false;
    }

    private static Map<String, Object> userResponse(Map<String, Object> row) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", ((Number) row.get("id")).longValue());
        user.put("email", row.get("email"));
        Object createdAt = row.get("created_at");
        user.put("createdAt", createdAt instanceof Timestamp ts
                ? ts.toInstant().toString()
                : createdAt == null ? null : createdAt.toString());
        return user;
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }
}
