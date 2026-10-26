package com.demo.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** FR-03 (hồ sơ người dùng) và FR-04 (lịch sử chỉnh sửa). */
@RestController
@RequestMapping("/api")
public class ProfileController {
    private static final Pattern PHONE_REGEX = Pattern.compile("^[0-9+()\\-.\\s]{6,30}$");
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 100;

    private final JdbcTemplate jdbcTemplate;
    private final AuthService authService;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ProfileController(JdbcTemplate jdbcTemplate, AuthService authService,
            PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.authService = authService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<Map<String, Object>> handleAuth(AuthException error) {
        return error(error.getStatus(), error.getMessage());
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> handleData(DataAccessException error) {
        System.err.println("Lỗi cơ sở dữ liệu: " + error.getMessage());
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Đã có lỗi xảy ra ở máy chủ. Vui lòng thử lại sau.");
    }

    /** Xem hồ sơ. User chỉ xem được của mình; admin có thể truyền userId để xem người khác. */
    @GetMapping("/profile")
    public ResponseEntity<Map<String, Object>> getProfile(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "userId", required = false) Long userId) {
        AuthService.Principal me = authService.authenticate(authorization);
        long targetId = userId == null ? me.id() : userId;
        requireCanAccess(me, targetId);

        Map<String, Object> profile = loadProfile(targetId);
        if (profile == null) {
            return error(HttpStatus.NOT_FOUND, "Không tìm thấy hồ sơ người dùng.");
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("profile", profile);
        return ResponseEntity.ok(response);
    }

    /** Admin tìm người dùng theo tên (hoặc email) để chọn chỉnh sửa / lọc lịch sử. */
    @GetMapping("/users")
    public ResponseEntity<Map<String, Object>> searchUsers(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "name", required = false, defaultValue = "") String name) {
        AuthService.Principal me = authService.authenticate(authorization);
        if (!me.isAdmin()) {
            return error(HttpStatus.FORBIDDEN, "Chỉ admin mới được tìm kiếm người dùng.");
        }
        String pattern = "%" + escapeLike(name.trim().toLowerCase(Locale.ROOT)) + "%";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT u.id, u.email, ui.name FROM users u JOIN user_info ui ON ui.id = u.id
                WHERE LOWER(ui.name) LIKE ? ESCAPE '\\' OR LOWER(u.email) LIKE ? ESCAPE '\\'
                ORDER BY ui.name, u.id LIMIT 20
                """, pattern, pattern);
        List<Map<String, Object>> users = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", ((Number) row.get("id")).longValue());
            item.put("email", row.get("email"));
            item.put("name", row.get("name"));
            users.add(item);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("users", users);
        return ResponseEntity.ok(response);
    }

    /** Chỉnh sửa hồ sơ và ghi lịch sử. Người thực hiện lấy từ JWT đã xác thực. */
    @PutMapping("/profile")
    public ResponseEntity<Map<String, Object>> updateProfile(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) JsonNode body) {
        final AuthService.Principal me = authService.authenticate(authorization);
        if (body == null || !body.isObject()) {
            return error(HttpStatus.BAD_REQUEST, "Dữ liệu gửi lên không hợp lệ.");
        }

        long parsedTargetId = me.id();
        if (body.hasNonNull("userId")) {
            if (!body.get("userId").canConvertToLong() || !body.get("userId").isIntegralNumber()) {
                return error(HttpStatus.BAD_REQUEST, "userId không hợp lệ.");
            }
            parsedTargetId = body.get("userId").asLong();
        }
        final long targetId = parsedTargetId;
        requireCanAccess(me, targetId);

        final String newName = body.hasNonNull("name") && body.get("name").isTextual()
                ? body.get("name").textValue().trim() : null;
        if (newName == null || newName.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "Tên không được để trống.");
        }
        if (newName.length() > 255) {
            return error(HttpStatus.BAD_REQUEST, "Tên tối đa 255 ký tự.");
        }

        final String newPhone;
        if (!body.hasNonNull("phoneNumber")) {
            newPhone = null;
        } else if (!body.get("phoneNumber").isTextual()) {
            return error(HttpStatus.BAD_REQUEST, "Số điện thoại không hợp lệ.");
        } else {
            String phone = body.get("phoneNumber").textValue().trim();
            if (phone.isEmpty()) {
                newPhone = null;
            } else if (!PHONE_REGEX.matcher(phone).matches()) {
                return error(HttpStatus.BAD_REQUEST,
                        "Số điện thoại chỉ gồm chữ số, khoảng trắng và + ( ) - . (6-30 ký tự).");
            } else {
                newPhone = phone;
            }
        }

        // Chỉ admin được đổi vai trò.
        final String newRole;
        if (body.hasNonNull("role")) {
            if (!body.get("role").isTextual()) {
                return error(HttpStatus.BAD_REQUEST, "Vai trò không hợp lệ.");
            }
            newRole = body.get("role").textValue();
            if (!"user".equals(newRole) && !"admin".equals(newRole)) {
                return error(HttpStatus.BAD_REQUEST, "Vai trò phải là 'user' hoặc 'admin'.");
            }
        } else {
            newRole = null;
        }

        record UpdateOutcome(boolean changed, ResponseEntity<Map<String, Object>> failure) {}

        UpdateOutcome outcome = transactionTemplate.execute(status -> {
            Map<String, Object> current;
            try {
                current = jdbcTemplate.queryForMap("""
                        SELECT ui.name, ui.phone_number, r.role_name FROM user_info ui
                        JOIN roles r ON r.role_id = ui.user_role WHERE ui.id = ? FOR UPDATE OF ui
                        """, targetId);
            } catch (EmptyResultDataAccessException notFound) {
                return new UpdateOutcome(false, error(HttpStatus.NOT_FOUND, "Không tìm thấy hồ sơ người dùng."));
            }

            String oldName = (String) current.get("name");
            String oldPhone = (String) current.get("phone_number");
            String oldRole = (String) current.get("role_name");

            ObjectNode diff = objectMapper.createObjectNode();
            if (!newName.equals(oldName)) {
                addDiff(diff, "name", oldName, newName);
            }
            if (!Objects.equals(newPhone, oldPhone)) {
                addDiff(diff, "phone_number", oldPhone, newPhone);
            }
            if (newRole != null && !newRole.equals(oldRole)) {
                if (!me.isAdmin()) {
                    return new UpdateOutcome(false, error(HttpStatus.FORBIDDEN, "Chỉ admin mới được thay đổi vai trò."));
                }
                if (me.id() == targetId) {
                    return new UpdateOutcome(false, error(HttpStatus.BAD_REQUEST, "Admin không thể tự đổi vai trò của mình."));
                }
                addDiff(diff, "role", oldRole, newRole);
            }

            if (diff.isEmpty()) {
                return new UpdateOutcome(false, null);
            }

            jdbcTemplate.update("""
                    UPDATE user_info SET name = ?, phone_number = ?,
                    user_role = (SELECT role_id FROM roles WHERE role_name = ?) WHERE id = ?
                    """, newName, newPhone, newRole != null ? newRole : oldRole, targetId);
            // Chụp tên người thực hiện tại thời điểm chỉnh sửa để lịch sử không đổi khi họ đổi tên sau này.
            String actorName = targetId == me.id() ? oldName
                    : jdbcTemplate.queryForObject("SELECT name FROM user_info WHERE id = ?", String.class, me.id());
            jdbcTemplate.update("""
                    INSERT INTO edit_history (actor_user_id, actor_name, target_user_id, changed_fields)
                    VALUES (?, ?, ?, ?::jsonb)
                    """, me.id(), actorName, targetId, diff.toString());
            return new UpdateOutcome(true, null);
        });

        if (outcome != null && outcome.failure() != null) {
            return outcome.failure();
        }
        boolean changed = outcome != null && outcome.changed();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", changed ? "Cập nhật thông tin thành công!" : "Không có thay đổi nào.");
        response.put("profile", loadProfile(targetId));
        return ResponseEntity.ok(response);
    }

    /**
     * Xem lịch sử. User chỉ thấy lịch sử của chính mình (người được chỉnh sửa = mình).
     * Admin thấy tất cả và có thể lọc theo actorId / targetId.
     */
    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> history(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "actorId", required = false) Long actorId,
            @RequestParam(value = "targetId", required = false) Long targetId,
            @RequestParam(value = "limit", required = false) Integer limitParam,
            @RequestParam(value = "offset", required = false) Integer offsetParam) {
        AuthService.Principal me = authService.authenticate(authorization);
        int limit = limitParam == null ? DEFAULT_PAGE_SIZE : Math.max(1, Math.min(limitParam, MAX_PAGE_SIZE));
        int offset = offsetParam == null ? 0 : Math.max(0, offsetParam);

        StringBuilder sql = new StringBuilder("""
                SELECT h.history_id, h.actor_user_id, h.target_user_id, h.changed_fields::text AS changed_fields,
                h.edited_at, COALESCE(h.actor_name, actor.name) AS actor_name, target.name AS target_name
                FROM edit_history h
                LEFT JOIN user_info actor ON actor.id = h.actor_user_id
                LEFT JOIN user_info target ON target.id = h.target_user_id WHERE 1=1""");
        List<Object> params = new ArrayList<>();
        if (me.isAdmin()) {
            if (actorId != null) {
                sql.append(" AND h.actor_user_id = ?");
                params.add(actorId);
            }
            if (targetId != null) {
                sql.append(" AND h.target_user_id = ?");
                params.add(targetId);
            }
        } else {
            // Quyền được áp dụng ở máy chủ: bỏ qua targetId do client gửi.
            sql.append(" AND h.target_user_id = ?");
            params.add(me.id());
            if (actorId != null) {
                sql.append(" AND h.actor_user_id = ?");
                params.add(actorId);
            }
        }
        // Lấy thêm 1 bản ghi để biết còn dữ liệu cũ hơn hay không.
        sql.append(" ORDER BY h.edited_at DESC, h.history_id DESC LIMIT ").append(limit + 1)
                .append(" OFFSET ").append(offset);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), params.toArray());
        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows = rows.subList(0, limit);
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", ((Number) row.get("history_id")).longValue());
            item.put("actorId", ((Number) row.get("actor_user_id")).longValue());
            item.put("actorName", row.get("actor_name"));
            item.put("targetId", ((Number) row.get("target_user_id")).longValue());
            item.put("targetName", row.get("target_name"));
            Object editedAt = row.get("edited_at");
            item.put("editedAt", editedAt instanceof Timestamp ts
                    ? ts.toInstant().toString() : String.valueOf(editedAt));
            try {
                item.put("changedFields", objectMapper.readTree((String) row.get("changed_fields")));
            } catch (JsonProcessingException e) {
                item.put("changedFields", null);
            }
            items.add(item);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("history", items);
        response.put("hasMore", hasMore);
        return ResponseEntity.ok(response);
    }

    private void requireCanAccess(AuthService.Principal me, long targetId) {
        if (!me.isAdmin() && me.id() != targetId) {
            throw new AuthException(HttpStatus.FORBIDDEN, "Bạn không có quyền truy cập hồ sơ của người dùng khác.");
        }
    }

    private Map<String, Object> loadProfile(long userId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT u.id, u.email, u.created_at, ui.name, ui.phone_number, r.role_name
                FROM users u JOIN user_info ui ON ui.id = u.id
                JOIN roles r ON r.role_id = ui.user_role WHERE u.id = ?
                """, userId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.getFirst();
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("id", ((Number) row.get("id")).longValue());
        profile.put("email", row.get("email"));
        profile.put("name", row.get("name"));
        profile.put("phoneNumber", row.get("phone_number"));
        profile.put("role", row.get("role_name"));
        Object createdAt = row.get("created_at");
        profile.put("createdAt", createdAt instanceof Timestamp ts
                ? ts.toInstant().toString()
                : createdAt == null ? null : createdAt.toString());
        return profile;
    }

    private static void addDiff(ObjectNode diff, String field, String before, String after) {
        ObjectNode entry = diff.putObject(field);
        entry.put("before", before);
        entry.put("after", after);
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }
}
