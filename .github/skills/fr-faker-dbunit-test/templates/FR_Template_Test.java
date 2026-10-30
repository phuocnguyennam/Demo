package com.demo.auth;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import net.datafaker.Faker;
import org.dbunit.Assertion;
import org.dbunit.dataset.Column;
import org.dbunit.dataset.IDataSet;
import org.dbunit.dataset.ITable;
import org.dbunit.dataset.ReplacementDataSet;
import org.dbunit.dataset.filter.DefaultColumnFilter;
import org.dbunit.dataset.xml.FlatXmlDataSetBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Khuôn mẫu chuẩn (Template) cho lớp kiểm thử tích hợp FR_<FeatureName>_Test
 * áp dụng Equivalence Partitioning (EP) & Boundary Value Analysis (BVA)
 * kết hợp Datafaker + Spring Boot TestRestTemplate + Testcontainers + DbUnit.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FR_Template_Test extends AbstractDbUnitTestSupport {

    private static final Logger log = LoggerFactory.getLogger(FR_Template_Test.class);
    private static final Path REPORT_FILE = Path.of("target", "faker-dbunit-<feature>-report.log");
    private static final Faker STATIC_FAKER = new Faker(Locale.ENGLISH);

    @BeforeAll
    static void initReportFile() throws Exception {
        Files.createDirectories(REPORT_FILE.getParent());
        Files.writeString(
                REPORT_FILE,
                "BÁO CÁO CHI TIẾT DỮ LIỆU FAKER & DBUNIT (FR-XX: TC01 -> TCxx)\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * Nhóm 1 (TC01 -> TCxx): EP Valid & BVA Valid.
     */
    static Stream<Arguments> validCases() {
        return Stream.of(
                Arguments.of("TC01 - EP Valid (...)", /* Faker arguments */ "")
        );
    }

    @Order(1)
    @ParameterizedTest(name = "{0}")
    @MethodSource("validCases")
    @DisplayName("TC01 -> TCxx (EP & BVA Hợp lệ): Faker -> API -> DbUnit")
    void shouldHandleValidCasesAndMatchDynamicDbUnitDataset(String testCaseName, String sampleArg) throws Exception {
        // 1. Chuẩn bị dữ liệu / Gửi request tới Backend qua hàm request(...) của AbstractDbUnitTestSupport
        // 2. Kiểm tra HTTP Status Code & Response Body
        // 3. Nạp ReplacementDataSet từ /datasets/expected-<feature>-success.xml và gán các biến [EXPECTED_*]
        // 4. Đọc actualDataSet = dbUnitConnection.createDataSet()
        // 5. Gọi logTestSnapshot(...) để in bảng ASCII ra Console và REPORT_FILE
        // 6. Dùng DefaultColumnFilter & Assertion.assertEquals(...) đối chiếu các bảng users, user_info, edit_history
    }

    private ReplacementDataSet loadReplacementDataSet(String classpathResource) throws Exception {
        try (InputStream is = getClass().getResourceAsStream(classpathResource)) {
            assertNotNull(is, "Không tìm thấy file dataset: " + classpathResource);
            IDataSet flatXmlDataSet = new FlatXmlDataSetBuilder()
                    .setColumnSensing(true)
                    .build(is);
            return new ReplacementDataSet(flatXmlDataSet);
        }
    }

    private void logTestSnapshot(
            String testCaseName,
            Map<String, Object> requestPayload,
            ResponseEntity<Map<String, Object>> response,
            IDataSet expectedDataSet,
            ITable actualUsersTable,
            ITable actualUserInfoTable) throws Exception {

        StringBuilder sb = new StringBuilder("\n");
        sb.append("================================================================================\n");
        sb.append(" KỊCH BẢN : ").append(testCaseName).append("\n");
        sb.append("--------------------------------------------------------------------------------\n");
        sb.append(" [1] DỮ LIỆU FAKER TẠO RA (REQUEST PAYLOAD):\n");
        for (Map.Entry<String, Object> entry : requestPayload.entrySet()) {
            Object val = entry.getValue();
            String lenInfo = (val instanceof String s) ? " (len=" + s.length() + ")" : "";
            sb.append(String.format("     - %-16s : %s%s%n", entry.getKey(), formatValue(val), lenInfo));
        }

        sb.append(" [2] KẾT QUẢ PHẢN HỒI TỪ BACKEND (HTTP RESPONSE):\n");
        sb.append("     - Status Code      : ").append(response.getStatusCode().value()).append("\n");
        sb.append("     - Response Body    : ").append(response.getBody()).append("\n");
        sb.append("--------------------------------------------------------------------------------\n");
        sb.append(" [3] BẢNG MONG ĐỢI ĐỘNG DBUNIT (EXPECTED DATASET):\n");
        sb.append(formatITable("expected.users", expectedDataSet.getTable("users")));
        sb.append(formatITable("expected.user_info", expectedDataSet.getTable("user_info")));
        sb.append("--------------------------------------------------------------------------------\n");
        sb.append(" [4] BẢNG THỰC TẾ TRONG POSTGRESQL (ACTUAL DATABASE):\n");
        sb.append(formatITable("actual.users", actualUsersTable));
        sb.append(formatITable("actual.user_info", actualUserInfoTable));
        sb.append("================================================================================");

        log.info(sb.toString());
        Files.writeString(
                REPORT_FILE,
                sb.append("\n").toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private static String formatITable(String tableTitle, ITable table) throws Exception {
        Column[] columns = table.getTableMetaData().getColumns();
        int rowCount = table.getRowCount();

        int[] widths = new int[columns.length];
        List<String[]> rows = new ArrayList<>(rowCount);

        for (int c = 0; c < columns.length; c++) {
            widths[c] = columns[c].getColumnName().length();
        }

        for (int r = 0; r < rowCount; r++) {
            String[] rowValues = new String[columns.length];
            for (int c = 0; c < columns.length; c++) {
                Object val = table.getValue(r, columns[c].getColumnName());
                String text = (val == null) ? "NULL" : String.valueOf(val);
                if ("password_hash".equals(columns[c].getColumnName()) && text.length() > 25) {
                    text = text.substring(0, 22) + "...";
                }
                rowValues[c] = text;
                widths[c] = Math.max(widths[c], text.length());
            }
            rows.add(rowValues);
        }

        StringBuilder border = new StringBuilder("     +");
        for (int w : widths) {
            border.append("-".repeat(w + 2)).append("+");
        }
        border.append("\n");

        StringBuilder out = new StringBuilder();
        out.append("   * Bảng ").append(tableTitle).append(" (").append(rowCount).append(" dòng):\n");
        out.append(border);

        out.append("     |");
        for (int c = 0; c < columns.length; c++) {
            out.append(String.format(" %-" + widths[c] + "s |", columns[c].getColumnName()));
        }
        out.append("\n").append(border);

        for (String[] row : rows) {
            out.append("     |");
            for (int c = 0; c < columns.length; c++) {
                out.append(String.format(" %-" + widths[c] + "s |", row[c]));
            }
            out.append("\n");
        }
        out.append(border);
        return out.toString();
    }

    private static String formatValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return "\"" + s + "\"";
        }
        return String.valueOf(value);
    }
}
