package ua.course.rental;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.*;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;
import net.jqwik.api.lifecycle.BeforeTry;
import net.jqwik.api.statistics.Statistics;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fuzz testing REST API: реальний SUT B (Spring Boot) + реальна test DB (PostgreSQL у Testcontainers).
 * Мета — стійкість до «брудних» input: жодного HTTP 500, лише очікувані статуси
 * і контракт помилки {code, message}. Бізнес-відмова (400/404/409) — це нормально.
 */
class RentalApiFuzzTest {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
            .withInitScript("migrations/V1__create_tables.sql");
    static final HttpClient http = HttpClient.newHttpClient();
    static final ObjectMapper json = new ObjectMapper();
    static final Object MISSING = new Object();   // маркер: поле видаляється з JSON
    static ConfigurableApplicationContext app;
    static String baseUrl;

    @BeforeContainer
    static void startSut() {
        postgres.start();
        app = new SpringApplicationBuilder(RentalApplication.class).run(
                "--server.port=0",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--RENTAL_POLICY_URL=");
        baseUrl = "http://127.0.0.1:" + ((ServletWebServerApplicationContext) app).getWebServer().getPort();
    }

    @AfterContainer
    static void stopSut() {
        if (app != null) app.close();
        postgres.stop();
    }

    /** Незалежність: перед кожною спробою (і при shrinking) БД чиста + одне відоме обладнання id=1 (100 грн, залишок 5). */
    @BeforeTry
    void resetDatabase() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE rental_items, rentals, equipment RESTART IDENTITY CASCADE");
        }
        var created = post("/equipment", "{\"code\":\"FUZZ-EQ\",\"dailyRate\":10000,\"stock\":5}");
        assertEquals(201, created.statusCode(), created.body());
    }

    // ================= fuzz targets (budget = tries) =================

    @Property(tries = 500)
    @Label("Fuzz 1. POST /equipment: жодного HTTP 500, лише 201/400/409")
    void fuzzCreateEquipment(@ForAll("equipmentBodies") String body) throws Exception {
        HttpResponse<String> response = post("/equipment", body);
        assertExpectedStatus(response, body, 201, 400, 409);

        if (response.statusCode() == 201) {   // інваріант: збережене обладнання в допустимому стані
            JsonNode saved = json.readTree(response.body());
            String code = saved.get("code").asText();
            assertTrue(!code.isBlank() && code.length() <= 40, "збережено некоректний код: " + code);
            assertTrue(saved.get("dailyRate").asLong() >= 100 && saved.get("dailyRate").asLong() <= 1_000_000);
            assertTrue(saved.get("stock").asInt() >= 0);
        }
    }

    @Property(tries = 500)
    @Label("Fuzz 2. POST /rentals: жодного HTTP 500, сума в межах, залишок не змінюється")
    void fuzzCreateRental(@ForAll("rentalBodies") String body) throws Exception {
        HttpResponse<String> response = post("/rentals", body);
        assertExpectedStatus(response, body, 201, 400, 404, 409);

        if (response.statusCode() == 201) {   // інваріант: сума не від'ємна і не більша за суму без знижки
            JsonNode rental = json.readTree(response.body());
            long subtotal = 10_000L * rental.get("item").get("quantity").asInt() * rental.get("days").asInt();
            long total = rental.get("total").asLong();
            assertTrue(total >= 0 && total <= subtotal, "сума поза межами: " + total);
            assertEquals("Active", rental.get("status").asText());
        }
        // інваріант стану: оформлення (успішне чи ні) не змінює залишок обладнання
        JsonNode equipment = json.readTree(get("/equipment/1").body());
        assertEquals(5, equipment.get("stock").asInt(), "залишок змінився");
    }

    // ================= custom generators «брудних» даних =================

    @Provide
    Arbitrary<String> equipmentBodies() {
        Arbitrary<Map<String, Object>> valid = Combinators.combine(
                        Arbitraries.strings().alpha().numeric().withChars('-').ofMinLength(1).ofMaxLength(40),
                        Arbitraries.longs().between(100, 1_000_000),
                        Arbitraries.integers().between(0, 100))
                .as((code, dailyRate, stock) -> body("code", code, "dailyRate", dailyRate, "stock", stock));
        return fuzzBodies(valid, "code", "dailyRate", "stock");
    }

    @Provide
    Arbitrary<String> rentalBodies() {
        Arbitrary<Map<String, Object>> valid = Combinators.combine(
                        Arbitraries.strings().alpha().numeric().withChars('-').ofMinLength(1).ofMaxLength(40),
                        Arbitraries.integers().between(1, 20),
                        Arbitraries.integers().between(1, 30),
                        Arbitraries.of(true, false))
                .as((reference, quantity, days, premium) -> body("reference", reference, "equipmentId", 1,
                        "quantity", quantity, "days", days, "premium", premium));
        return fuzzBodies(valid, "reference", "equipmentId", "quantity", "days", "premium");
    }

    /**
     * Мутаційний fuzz: береться валідний запит, і одне його поле замінюється «брудним» значенням
     * або видаляється; частина запитів лишається валідною, частина — зламаний JSON.
     */
    Arbitrary<String> fuzzBodies(Arbitrary<Map<String, Object>> valid, String... fields) {
        Arbitrary<String> mutated = Combinators.combine(valid, Arbitraries.of(fields), dirtyValues())
                .as((body, field, value) -> {
                    Map<String, Object> copy = new LinkedHashMap<>(body);
                    if (value == MISSING) copy.remove(field);
                    else copy.put(field, value);
                    return toJson(copy);
                });
        return Arbitraries.frequencyOf(
                Tuple.of(2, valid.map(RentalApiFuzzTest::toJson)),
                Tuple.of(7, mutated),
                Tuple.of(1, malformedJson()));
    }

    /** «Брудні» значення: межі довжини 40, довільні Unicode-символи (зокрема \u0000), спецрядки, межі чисел ±1, екстремуми, не той тип. */
    Arbitrary<Object> dirtyValues() {
        return Arbitraries.<Object>frequencyOf(
                Tuple.of(2, Arbitraries.strings().alpha().ofMinLength(39).ofMaxLength(42)),
                Tuple.of(2, Arbitraries.strings().ofMaxLength(50)),
                Tuple.of(2, Arbitraries.of("", " ", "\t", "\u0000", "RENT\u0000", "'; DROP TABLE rentals; --",
                        "<script>alert(1)</script>", "😀", "null", "Код-Ї")),
                Tuple.of(2, Arbitraries.of(0L, -1L, 21L, 31L, 99L, 1_000_001L, Integer.MAX_VALUE + 1L, Long.MIN_VALUE, Long.MAX_VALUE)),
                Tuple.of(1, Arbitraries.longs()),
                Tuple.of(1, Arbitraries.of(1.5, -0.0, 1e20)),
                Tuple.of(1, Arbitraries.of("true", "yes", "10", "abc")),
                Tuple.of(1, Arbitraries.of(true, false)),
                Tuple.of(1, Arbitraries.of(List.of(1, 2), Map.of("a", 1))),
                Tuple.of(1, Arbitraries.just(MISSING))
        ).injectNull(0.05);
    }

    /** Зламаний JSON. */
    Arbitrary<String> malformedJson() {
        return Arbitraries.of("", "{", "}", "[]", "null", "not json", "{\"code\":}", "{\"reference\":\"A\",}",
                "\u0000", "[".repeat(2000));
    }

    // ================= helpers =================

    static Map<String, Object> body(Object... keyValues) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) body.put((String) keyValues[i], keyValues[i + 1]);
        return body;
    }

    static String toJson(Map<String, Object> body) {
        try {
            return json.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static void assertExpectedStatus(HttpResponse<String> response, String body, Integer... allowed) throws Exception {
        Statistics.label("HTTP статус").collect(response.statusCode());
        assertTrue(Set.of(allowed).contains(response.statusCode()),
                () -> "Неочікуваний статус " + response.statusCode() + " для " + body + " -> " + response.body());
        if (response.statusCode() >= 400) {
            JsonNode error = json.readTree(response.body());
            assertTrue(error.hasNonNull("code") && error.hasNonNull("message"),
                    "порушено контракт помилки {code, message}: " + response.body());
        }
    }

    static HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // ================= deterministic regression / boundary tests з counterexample fuzz =================

    @Example
    @Label("Regression: код обладнання з символом \\u0000 -> 400, а не 500")
    void regressionNullByteInEquipmentCode() throws Exception {
        HttpResponse<String> response = post("/equipment", "{\"code\":\"\\u0000\",\"dailyRate\":100,\"stock\":0}");
        assertEquals(400, response.statusCode(), response.body());
    }

    @Example
    @Label("Regression: код обладнання 41 символ -> 400, а не 500")
    void regressionTooLongEquipmentCode() throws Exception {
        HttpResponse<String> response = post("/equipment", toJson(body("code", "A".repeat(41), "dailyRate", 100, "stock", 0)));
        assertEquals(400, response.statusCode(), response.body());
    }

    @Example
    @Label("Boundary: код обладнання рівно 40 символів -> 201")
    void boundaryEquipmentCodeOf40Chars() throws Exception {
        HttpResponse<String> response = post("/equipment", toJson(body("code", "A".repeat(40), "dailyRate", 100, "stock", 0)));
        assertEquals(201, response.statusCode(), response.body());
    }

    @Example
    @Label("Regression: код оренди з символом \\u0000 -> 400, а не 500")
    void regressionNullByteInRentalReference() throws Exception {
        HttpResponse<String> response = post("/rentals",
                "{\"reference\":\"\\u0000\",\"equipmentId\":1,\"quantity\":1,\"days\":1,\"premium\":false}");
        assertEquals(400, response.statusCode(), response.body());
    }

    @Example
    @Label("Regression: код обладнання лише з табуляції (\\t) -> 400, а не 201")
    void regressionBlankEquipmentCode() throws Exception {
        HttpResponse<String> response = post("/equipment", "{\"code\":\"\\t\",\"dailyRate\":100,\"stock\":0}");
        assertEquals(400, response.statusCode(), response.body());
    }
}