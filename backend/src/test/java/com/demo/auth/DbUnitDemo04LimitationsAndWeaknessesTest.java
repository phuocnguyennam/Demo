package com.demo.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.io.StringReader;
import java.util.Locale;
import java.util.Map;
import org.dbunit.Assertion;
import org.dbunit.DatabaseUnitException;
import org.dbunit.database.DatabaseConfig;
import org.dbunit.database.DatabaseDataSourceConnection;
import org.dbunit.database.IDatabaseConnection;
import org.dbunit.dataset.IDataSet;
import org.dbunit.dataset.ITable;
import org.dbunit.dataset.NoSuchColumnException;
import org.dbunit.dataset.ReplacementDataSet;
import org.dbunit.dataset.SortedTable;
import org.dbunit.dataset.filter.DefaultColumnFilter;
import org.dbunit.dataset.xml.FlatXmlDataSetBuilder;
import org.dbunit.ext.postgresql.PostgresqlDataTypeFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * KỊCH BẢN DEMO 04: MINH HỌA CÁC ĐIỂM YẾU & HẠN CHẾ CỦA CÔNG CỤ DBUNIT TRONG THỰC TẾ.
 *
 * File test này chứng minh trực tiếp bằng code các trường hợp DBUnit bộc lộ điểm yếu,
 * không giải quyết tốt mục tiêu đặt ra nếu chỉ dùng mặc định, kèm theo hướng khắc phục:
 *
 * 1. Điểm yếu 1 (Kiểu dữ liệu phi truyền thống - JSONB):
 *    - PostgresqlDataTypeFactory mặc định của DBUnit không nhận diện được cột JSONB của PostgreSQL,
 *      khiến cột changed_fields bị bỏ qua và ném NoSuchColumnException.
 *    - Ngay cả khi ép về VARCHAR, DBUnit chỉ so sánh chuỗi ký tự thuần túy (String comparison) chứ không hiểu
 *      cấu trúc ngữ nghĩa JSON (khác thứ tự key là báo lỗi).
 *
 * 2. Điểm yếu 2 (Dữ liệu sinh động / băm ngẫu nhiên - BCrypt, Timestamp, Auto-increment ID):
 *    - Không thể dùng XML tĩnh của DBUnit để xác minh trực tiếp cột password_hash (do BCrypt sinh salt ngẫu nhiên).
 *    - Nếu dùng DefaultColumnFilter để loại bỏ cột thì DBUnit lại không kiểm chứng được tính đúng đắn của mật khẩu.
 *
 * 3. Điểm yếu 3 (So sánh theo vị trí dòng cứng nhắc & lỗi sắp xếp số dạng chuỗi của SortedTable):
 *    - Khác với lý thuyết quan hệ (bảng là tập hợp không thứ tự), DBUnit so sánh từng dòng theo chỉ số index (0, 1, ...).
 *    - Hơn nữa, SortedTable của DBUnit mặc định so sánh mọi kiểu dữ liệu (kể cả số nguyên INTEGER) dưới dạng String
 *      ("10" đứng trước "2"), gây lỗi sai thứ tự nếu quên gọi setUseComparable(true).
 *
 * 4. Điểm yếu 4 (Bẫy "Silent False Positive" khi dòng đầu tiên trong Flat XML có cột NULL):
 *    - Mặc định FlatXmlDataSetBuilder chỉ đọc danh sách cột từ dòng đầu tiên của mỗi bảng trong XML.
 *      Nếu dòng đầu lược bỏ thuộc tính có giá trị NULL (như phone_number của Admin) và quên bật columnSensing,
 *      DBUnit sẽ âm thầm bỏ qua không kiểm tra cột đó ở mọi dòng tiếp theo dù dữ liệu bị sai!
 */
class DbUnitDemo04LimitationsAndWeaknessesTest extends AbstractDbUnitTestSupport {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Điểm yếu 1: DBUnit mặc định không đọc được cột JSONB và không hiểu ngữ nghĩa JSON khi so sánh")
    void weakness01_JsonbDataTypeAndSemanticJsonComparisonLimitation() throws Exception {
        // Tạo 1 user và cập nhật hồ sơ để sinh 1 bản ghi có cột changed_fields (JSONB) trong edit_history
        String email = faker.internet().emailAddress().toLowerCase(Locale.ROOT);
        String initialName = email.substring(0, email.indexOf('@'));
        String password = generateValidPassword();
        String newName = faker.name().fullName();

        request("/api/register", HttpMethod.POST,
                Map.of("email", email, "password", password, "confirmPassword", password), null);
        String token = (String) request("/api/login", HttpMethod.POST,
                Map.of("email", email, "password", password), null).getBody().get("token");
        request("/api/profile", HttpMethod.PUT, Map.of("name", newName), token);

        // --- MINH HỌA 1A: PostgresqlDataTypeFactory chuẩn của DBUnit bỏ sót cột JSONB ---
        IDatabaseConnection defaultPgConnection = new DatabaseDataSourceConnection(dataSource);
        defaultPgConnection.getConfig().setProperty(
                DatabaseConfig.PROPERTY_DATATYPE_FACTORY, new PostgresqlDataTypeFactory());
        try {
            ITable historyTableWithDefaultFactory = defaultPgConnection.createDataSet().getTable("edit_history");
            // Khi cố truy cập cột changed_fields (kiểu JSONB), DBUnit ném NoSuchColumnException vì DataType.UNKNOWN bị lọc bỏ!
            assertThrows(NoSuchColumnException.class,
                    () -> historyTableWithDefaultFactory.getValue(0, "changed_fields"),
                    "PostgresqlDataTypeFactory mặc định không hỗ trợ kiểu JSONB và làm mất cột changed_fields");
        } finally {
            defaultPgConnection.close();
        }

        // --- MINH HỌA 1B: DBUnit so sánh JSONB như chuỗi thuần túy, báo lỗi khi khác thứ tự key ---
        // Chuỗi JSON kỳ vọng hoàn toàn hợp lệ về mặt ngữ nghĩa (chứa đúng "before" và "after"),
        // nhưng viết "before" trước "after", trong khi PostgreSQL JSONB chuẩn hóa "after" (5 ký tự) trước "before" (6 ký tự).
        String semanticallyEquivalentJson = String.format(
                "{\"name\": {\"before\": \"%s\", \"after\": \"%s\"}}", initialName, newName);

        ITable actualHistory = dbUnitConnection.createDataSet().getTable("edit_history");
        String actualJsonInDb = (String) actualHistory.getValue(0, "changed_fields");

        // Jackson xác nhận 2 chuỗi JSON có cấu trúc và dữ liệu hoàn toàn bằng nhau:
        assertEquals(
                objectMapper.readTree(semanticallyEquivalentJson),
                objectMapper.readTree(actualJsonInDb),
                "Về mặt ngữ nghĩa JSON, hai giá trị hoàn toàn tương đương"
        );

        // Nhưng DBUnit chỉ so sánh String nên sẽ thất bại!
        String expectedXml = String.format("""
                <dataset>
                    <edit_history changed_fields='%s' />
                </dataset>
                """, semanticallyEquivalentJson);
        ITable expectedTable = new FlatXmlDataSetBuilder().build(new StringReader(expectedXml)).getTable("edit_history");
        ITable filteredActual = DefaultColumnFilter.includedColumnsTable(actualHistory, new String[]{"changed_fields"});

        assertThrows(AssertionError.class,
                () -> Assertion.assertEquals(expectedTable, filteredActual),
                "DBUnit thất bại khi so sánh JSON có thứ tự key khác nhau vì chỉ so sánh chuỗi ký tự");
    }

    @Test
    @DisplayName("Điểm yếu 2: DBUnit không thể đối chiếu trực tiếp các cột sinh ngẫu nhiên (BCrypt password_hash)")
    void weakness02_CannotDirectlyAssertNonDeterministicBcryptColumn() throws Exception {
        String email = faker.internet().emailAddress().toLowerCase(Locale.ROOT);
        String password = generateValidPassword();

        request("/api/register", HttpMethod.POST,
                Map.of("email", email, "password", password, "confirmPassword", password), null);

        // Cùng 1 mật khẩu nhưng BCrypt sinh salt ngẫu nhiên mỗi lần băm
        String expectedHashInXml = passwordEncoder.encode(password);
        ITable actualUsers = dbUnitConnection.createQueryTable(
                "users", "SELECT email, password_hash FROM users WHERE email = '" + email + "'");
        String actualHashInDb = (String) actualUsers.getValue(0, "password_hash");

        // Hai chuỗi hash khác nhau dù cùng biểu diễn 1 mật khẩu
        assertNotEquals(expectedHashInXml, actualHashInDb);

        String expectedXml = String.format("""
                <dataset>
                    <users email="%s" password_hash="%s" />
                </dataset>
                """, email, expectedHashInXml);
        ITable expectedTable = new FlatXmlDataSetBuilder().build(new StringReader(expectedXml)).getTable("users");

        // DBUnit sẽ báo lỗi nếu không loại bỏ cột password_hash khỏi phép so sánh
        assertThrows(AssertionError.class,
                () -> Assertion.assertEquals(expectedTable, actualUsers),
                "DBUnit không hỗ trợ custom matcher (như BCrypt.matches) ngay trong XML Dataset");
    }

    @Test
    @DisplayName("Điểm yếu 3: DBUnit so sánh cứng nhắc theo vị trí dòng và SortedTable mặc định sắp xếp số theo bảng chữ cái ('10' < '2')")
    void weakness03_PositionalRowComparisonAndStringBasedSortingQuirk() throws Exception {
        // 1. Tạo 1 user mới bằng Faker
        String email = faker.internet().emailAddress().toLowerCase(Locale.ROOT);
        String defaultUserName = email.substring(0, email.indexOf('@'));
        String password = generateValidPassword();
        request("/api/register", HttpMethod.POST,
                Map.of("email", email, "password", password, "confirmPassword", password), null);

        // --- MINH HỌA 3A: DBUnit không coi bảng là tập hợp (Set), đảo vị trí dòng trong XML sẽ báo lỗi ---
        // Người viết test đặt dòng User vừa đăng ký lên đầu và Admin ở dòng thứ 2 trong XML
        String reversedOrderXml = String.format("""
                <dataset>
                    <users email="%s" />
                    <users email="admin@example.com" />
                </dataset>
                """, email);
        ITable reversedExpected = new FlatXmlDataSetBuilder().build(new StringReader(reversedOrderXml)).getTable("users");
        ITable actualUsersFiltered = DefaultColumnFilter.excludedColumnsTable(
                dbUnitConnection.createDataSet().getTable("users"),
                new String[]{"id", "password_hash", "created_at"}
        );

        // Dù cả 2 bảng chứa đúng tập 2 email như nhau, DBUnit vẫn ném AssertionError vì so sánh theo index dòng!
        assertThrows(AssertionError.class,
                () -> Assertion.assertEquals(reversedExpected, actualUsersFiltered),
                "DBUnit so sánh từng dòng theo vị trí index chứ không đối chiếu theo tập hợp bản ghi");

        // --- MINH HỌA 3B: SortedTable của DBUnit mặc định so sánh cột số nguyên theo kiểu String ("10" đứng trước "2") ---
        ITable numbersTable = dbUnitConnection.createQueryTable(
                "num_order",
                "SELECT 2 AS id, 'User Two' AS name UNION ALL SELECT 10 AS id, 'User Ten' AS name"
        );
        // Mặc định SortedTable(table, columns) có useComparable = false -> ép sang String và xếp id=10 lên trước id=2!
        SortedTable defaultSorted = new SortedTable(numbersTable, new String[]{"id"});
        assertEquals(10, defaultSorted.getValue(0, "id"),
                "Mặc định SortedTable so sánh dạng chuỗi nên xếp 10 đứng trước 2");
        assertEquals(2, defaultSorted.getValue(1, "id"));

        // Cách khắc phục: Bắt buộc bật setUseComparable(true) để sắp xếp theo giá trị số thực tế
        SortedTable comparableSorted = new SortedTable(numbersTable, new String[]{"id"});
        comparableSorted.setUseComparable(true);
        assertEquals(2, comparableSorted.getValue(0, "id"));
        assertEquals(10, comparableSorted.getValue(1, "id"));
    }

    @Test
    @DisplayName("Điểm yếu 4: Bẫy 'Silent False Positive' (Bỏ lọt lỗi) khi dòng đầu trong XML thiếu cột NULL và không bật Column Sensing")
    void weakness04_SilentFalsePositiveWhenColumnSensingIsDisabled() throws Exception {
        // Tạo 1 user và cập nhật số điện thoại thực tế trong DB thành "+84 999 888 777"
        String email = faker.internet().emailAddress().toLowerCase(Locale.ROOT);
        String password = generateValidPassword();
        String updatedName = faker.name().fullName();

        request("/api/register", HttpMethod.POST,
                Map.of("email", email, "password", password, "confirmPassword", password), null);
        String token = (String) request("/api/login", HttpMethod.POST,
                Map.of("email", email, "password", password), null).getBody().get("token");
        ResponseEntity<Map<String, Object>> updateRes = request("/api/profile", HttpMethod.PUT,
                Map.of("name", updatedName, "phoneNumber", "+84 999 888 777"), token);
        assertEquals(200, updateRes.getStatusCode().value());

        // Trong file demo04-column-sensing-trap.xml:
        // - Dòng 1 (Admin) không ghi thuộc tính phone_number (vì là NULL).
        // - Dòng 2 (User) ghi phone_number="+00 000 000 000" (SAI hoàn toàn so với "+84 999 888 777" trong DB!).
        try (InputStream isWithoutSensing = getClass().getResourceAsStream("/datasets/demo04-column-sensing-trap.xml");
             InputStream isWithSensing = getClass().getResourceAsStream("/datasets/demo04-column-sensing-trap.xml")) {

            // 1. Dùng FlatXmlDataSetBuilder MẶC ĐỊNH (columnSensing = false)
            ReplacementDataSet trapDataSet = new ReplacementDataSet(
                    new FlatXmlDataSetBuilder().build(isWithoutSensing));
            trapDataSet.addReplacementSubstring("${updated_name}", updatedName);
            trapDataSet.addReplacementSubstring("${admin_role_id}", String.valueOf(roleId("admin")));
            trapDataSet.addReplacementSubstring("${user_role_id}", String.valueOf(roleId("user")));

            ITable trapExpectedTable = trapDataSet.getTable("user_info");
            SortedTable sortedActual = new SortedTable(
                    dbUnitConnection.createDataSet().getTable("user_info"), new String[]{"id"});
            sortedActual.setUseComparable(true);

            // Lọc chỉ lấy các cột có trong trapExpectedTable (chỉ gồm name và user_role, mất cột phone_number!)
            ITable actualFilteredByTrapMetaData = DefaultColumnFilter.includedColumnsTable(
                    sortedActual, trapExpectedTable.getTableMetaData().getColumns());

            // NGUY HIỂM: Phép kiểm tra dưới đây VẪN PASS dù phone_number trong XML ("+00 000 000 000")
            // khác hoàn toàn trong DB ("+84 999 888 777")!
            Assertion.assertEquals(trapExpectedTable, actualFilteredByTrapMetaData);

            // 2. Cách khắc phục: Bắt buộc bật .setColumnSensing(true) (hoặc dùng [NULL] ở dòng đầu)
            ReplacementDataSet sensedDataSet = new ReplacementDataSet(
                    new FlatXmlDataSetBuilder().setColumnSensing(true).build(isWithSensing));
            sensedDataSet.addReplacementSubstring("${updated_name}", updatedName);
            sensedDataSet.addReplacementSubstring("${admin_role_id}", String.valueOf(roleId("admin")));
            sensedDataSet.addReplacementSubstring("${user_role_id}", String.valueOf(roleId("user")));

            ITable sensedExpectedTable = sensedDataSet.getTable("user_info");
            ITable actualWithoutId = DefaultColumnFilter.excludedColumnsTable(sortedActual, new String[]{"id"});

            // Lúc này DBUnit mới phát hiện ra số điện thoại "+00 000 000 000" != "+84 999 888 777" và ném AssertionError!
            assertThrows(AssertionError.class,
                    () -> Assertion.assertEquals(sensedExpectedTable, actualWithoutId),
                    "Khi bật columnSensing(true), DBUnit mới phát hiện đúng sự sai lệch ở cột phone_number");
        }
    }
}

