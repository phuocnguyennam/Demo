package com.demo.auth;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AuthenticationFlowIntegrationTest {
    private static final String ADMIN_EMAIL = "admin@example.com";
    private static final String ADMIN_PASSWORD = "Admin@123456";
    private static final String USER_EMAIL = "test.user@example.com";
    private static final String USER_PASSWORD = "UserPass1!";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("integration")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("DATABASE_URL", POSTGRES::getJdbcUrl);
        registry.add("DB_USER", POSTGRES::getUsername);
        registry.add("DB_PASSWORD", POSTGRES::getPassword);
        registry.add("JWT_SECRET", () -> "integration-test-secret");
        registry.add("ADMIN_EMAIL", () -> ADMIN_EMAIL);
        registry.add("ADMIN_PASSWORD", () -> ADMIN_PASSWORD);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @LocalServerPort
    private int port;

    @Test
    void supportsDocumentedRegistrationLoginProfileAndHistoryFlows() {
        ResponseEntity<Map<String, Object>> health = request(
                "/api/health", HttpMethod.GET, null, null);
        assertEquals(200, health.getStatusCode().value());
        assertEquals("connected", health.getBody().get("database"));

        assertEquals(400, request("/api/register", HttpMethod.POST,
                registration("invalid-email", USER_PASSWORD, USER_PASSWORD), null)
                .getStatusCode().value());
        assertEquals(400, request("/api/register", HttpMethod.POST,
                registration(USER_EMAIL, "weak", "weak"), null)
                .getStatusCode().value());
        assertEquals(400, request("/api/register", HttpMethod.POST,
                registration(USER_EMAIL, USER_PASSWORD, "Different1!"), null)
                .getStatusCode().value());

        ResponseEntity<Map<String, Object>> registered = request("/api/register", HttpMethod.POST,
                registration("  Test.User@Example.com ", USER_PASSWORD, USER_PASSWORD), null);
        assertEquals(201, registered.getStatusCode().value());
        Map<?, ?> registeredUser = map(registered.getBody().get("user"));
        assertEquals(USER_EMAIL, registeredUser.get("email"));
        assertFalse(registeredUser.containsKey("password"));
        long userId = number(registeredUser.get("id"));

        assertEquals(409, request("/api/register", HttpMethod.POST,
                registration(USER_EMAIL, USER_PASSWORD, USER_PASSWORD), null)
                .getStatusCode().value());
        assertEquals(401, request("/api/login", HttpMethod.POST,
                Map.of("email", USER_EMAIL, "password", "WrongPass1!"), null)
                .getStatusCode().value());

        ResponseEntity<Map<String, Object>> userLogin = request("/api/login", HttpMethod.POST,
                Map.of("email", USER_EMAIL.toUpperCase(), "password", USER_PASSWORD), null);
        assertEquals(200, userLogin.getStatusCode().value());
        String userToken = (String) userLogin.getBody().get("token");
        assertNotNull(userToken);

        ResponseEntity<Map<String, Object>> restoredSession = request(
                "/api/me", HttpMethod.GET, null, userToken);
        assertEquals(200, restoredSession.getStatusCode().value());
        assertEquals(USER_EMAIL, map(restoredSession.getBody().get("user")).get("email"));

        ResponseEntity<Map<String, Object>> userProfile = request(
                "/api/profile", HttpMethod.GET, null, userToken);
        assertEquals(200, userProfile.getStatusCode().value());
        assertEquals("user", map(userProfile.getBody().get("profile")).get("role"));

        ResponseEntity<Map<String, Object>> updatedUser = request(
                "/api/profile", HttpMethod.PUT,
                Map.of("name", "Alice Updated", "phoneNumber", "+1 555 0101"), userToken);
        assertEquals(200, updatedUser.getStatusCode().value());
        assertEquals("Alice Updated", map(updatedUser.getBody().get("profile")).get("name"));

        ResponseEntity<Map<String, Object>> userHistory = request(
                "/api/history?targetId=999999", HttpMethod.GET, null, userToken);
        assertEquals(200, userHistory.getStatusCode().value());
        List<?> userHistoryItems = list(userHistory.getBody().get("history"));
        assertEquals(1, userHistoryItems.size());
        Map<?, ?> userEdit = map(userHistoryItems.getFirst());
        assertEquals(userId, number(userEdit.get("targetId")));
        assertEquals("test.user", userEdit.get("actorName"));

        ResponseEntity<Map<String, Object>> adminLogin = request("/api/login", HttpMethod.POST,
                Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD), null);
        assertEquals(200, adminLogin.getStatusCode().value());
        String adminToken = (String) adminLogin.getBody().get("token");
        long adminId = number(map(adminLogin.getBody().get("user")).get("id"));

        assertEquals(403, request("/api/profile?userId=" + adminId, HttpMethod.GET, null, userToken)
                .getStatusCode().value());
        assertEquals(403, request("/api/users?name=Alice", HttpMethod.GET, null, userToken)
                .getStatusCode().value());

        ResponseEntity<Map<String, Object>> search = request(
                "/api/users?name=Alice", HttpMethod.GET, null, adminToken);
        assertEquals(200, search.getStatusCode().value());
        assertEquals(userId, number(map(list(search.getBody().get("users")).getFirst()).get("id")));

        ResponseEntity<Map<String, Object>> adminEdit = request(
                "/api/profile", HttpMethod.PUT,
                Map.of("userId", userId, "name", "Alice Admin Edit", "phoneNumber", "+1 555 0102",
                        "role", "admin"),
                adminToken);
        assertEquals(200, adminEdit.getStatusCode().value());
        assertEquals("admin", map(adminEdit.getBody().get("profile")).get("role"));

        ResponseEntity<Map<String, Object>> adminHistory = request(
                "/api/history?limit=1&targetId=" + userId, HttpMethod.GET, null, adminToken);
        assertEquals(200, adminHistory.getStatusCode().value());
        assertEquals(1, list(adminHistory.getBody().get("history")).size());
        assertTrue(Boolean.TRUE.equals(adminHistory.getBody().get("hasMore")));

        ResponseEntity<Map<String, Object>> filteredHistory = request(
                "/api/history?actorId=" + adminId + "&targetId=" + userId,
                HttpMethod.GET, null, adminToken);
        assertEquals(200, filteredHistory.getStatusCode().value());
        Map<?, ?> changedByAdmin = map(list(filteredHistory.getBody().get("history")).getFirst());
        assertEquals("Admin", changedByAdmin.get("actorName"));
        assertEquals(userId, number(changedByAdmin.get("targetId")));

        ResponseEntity<Map<String, Object>> selfRoleChange = request(
                "/api/profile", HttpMethod.PUT,
                Map.of("userId", adminId, "name", "Admin", "role", "user"), adminToken);
        assertEquals(400, selfRoleChange.getStatusCode().value());
    }

    private ResponseEntity<Map<String, Object>> request(
            String path, HttpMethod method, Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(
                "http://localhost:" + port + path,
                method,
                new HttpEntity<>(body, headers),
                new ParameterizedTypeReference<>() {});
    }

    private static Map<String, Object> registration(String email, String password, String confirmPassword) {
        return Map.of("email", email, "password", password, "confirmPassword", confirmPassword);
    }

    private static Map<?, ?> map(Object value) {
        return (Map<?, ?>) value;
    }

    private static List<?> list(Object value) {
        return (List<?>) value;
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }
}
