package ua.course.rental;

import io.restassured.RestAssured;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Лабораторна робота №6: автоматизоване тестування REST API.
 *
 * Застосунок (SUT B, прокат обладнання, варіант 04) запускається цілком на випадковому порту,
 * PostgreSQL — у Docker-контейнері. Тести надсилають справжні HTTP-запити через RestAssured
 * і перевіряють status code та JSON-відповідь, а не викликають сервіс напряму.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DisplayName("REST API прокату обладнання")
class RentalApiTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("rental_variant04_test")
            .withUsername("rental_student")
            .withPassword("student")
            .withInitScript("migrations/V1__create_tables.sql");

    // Застосунок підключається до бази з контейнера; зовнішню HTTP-політику вимкнено (локальна політика дозволяє оренду)
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("RENTAL_POLICY_URL", () -> "");
    }

    @LocalServerPort
    int port;

    @BeforeAll
    static void logFailures() {
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }

    @BeforeEach
    void setUp() {
        RestAssured.baseURI = "http://127.0.0.1";
        RestAssured.port = port;
    }

    // ---------- допоміжні методи ----------

    private String uniqueCode() {
        return "EQ-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String uniqueReference() {
        return "REF-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private long createEquipment(long dailyRate, int stock) {
        return given().contentType(JSON)
                .body(Map.of("code", uniqueCode(), "dailyRate", dailyRate, "stock", stock))
                .when().post("/equipment")
                .then().statusCode(201)
                .extract().jsonPath().getLong("id");
    }

    private Map<String, Object> rentalBody(String reference, long equipmentId, int quantity, int days, boolean premium) {
        return Map.of("reference", reference, "equipmentId", equipmentId, "quantity", quantity, "days", days, "premium", premium);
    }

    private long createRental(long equipmentId, int quantity, int days, boolean premium) {
        return given().contentType(JSON)
                .body(rentalBody(uniqueReference(), equipmentId, quantity, days, premium))
                .when().post("/rentals")
                .then().statusCode(201)
                .extract().jsonPath().getLong("id");
    }

    // ---------- 1. POST: створення ресурсу ----------

    @Test
    @DisplayName("POST /equipment: 201, id, Location і поля обладнання")
    void createEquipment_returns201WithIdAndFields() {
        String code = uniqueCode();

        ExtractableResponse<Response> response = given().contentType(JSON)
                .body(Map.of("code", code, "dailyRate", 10000, "stock", 5))
                .when().post("/equipment")
                .then().statusCode(201)
                .body("id", greaterThan(0))
                .body("code", equalTo(code))
                .body("dailyRate", equalTo(10000))
                .body("stock", equalTo(5))
                .extract();

        long id = response.jsonPath().getLong("id");
        assertTrue(response.header("Location").endsWith("/equipment/" + id));
    }

    @Test
    @DisplayName("POST /rentals: 201, id, Location і поля оренди")
    void createRental_returns201WithIdAndFields() {
        long equipmentId = createEquipment(10000, 5);
        String reference = uniqueReference();

        ExtractableResponse<Response> response = given().contentType(JSON)
                .body(rentalBody(reference, equipmentId, 2, 3, false))
                .when().post("/rentals")
                .then().statusCode(201)
                .body("id", greaterThan(0))
                .body("reference", equalTo(reference))
                .body("days", equalTo(3))
                .body("premium", equalTo(false))
                .body("total", equalTo(60000))          // 10000 × 2 × 3, без знижки
                .body("lateFee", equalTo(0))
                .body("status", equalTo("Active"))
                .body("item.equipmentId", equalTo((int) equipmentId))
                .body("item.quantity", equalTo(2))
                .body("item.dailyRate", equalTo(10000))
                .extract();

        long id = response.jsonPath().getLong("id");
        assertTrue(response.header("Location").endsWith("/rentals/" + id));
    }

    // ---------- 2. GET за id ----------

    @Test
    @DisplayName("GET /rentals/{id}: 200, дані збігаються зі створеною орендою")
    void getRental_returns200WithSameDataAsCreated() {
        long equipmentId = createEquipment(10000, 5);
        ExtractableResponse<Response> created = given().contentType(JSON)
                .body(rentalBody(uniqueReference(), equipmentId, 2, 3, false))
                .when().post("/rentals")
                .then().statusCode(201)
                .extract();
        long id = created.jsonPath().getLong("id");

        ExtractableResponse<Response> fetched = given()
                .when().get("/rentals/{id}", id)
                .then().statusCode(200)
                .body("id", equalTo((int) id))
                .body("status", equalTo("Active"))
                .extract();

        assertEquals(created.jsonPath().getMap("$"), fetched.jsonPath().getMap("$"));
    }

    // ---------- 3. GET неіснуючого ресурсу ----------

    @ParameterizedTest(name = "GET {0} -> 404")
    @CsvSource({
            "/rentals/999999999,   Оренду не знайдено",
            "/equipment/999999999, Обладнання не знайдено"
    })
    @DisplayName("GET неіснуючого ресурсу: 404, code і message")
    void getMissingResource_returns404WithErrorContract(String path, String expectedMessage) {
        given()
                .when().get(path)
                .then().statusCode(404)
                .body("code", equalTo("NOT_FOUND"))
                .body("message", equalTo(expectedMessage));
    }

    // ---------- 4. Action endpoint ----------

    @Test
    @DisplayName("POST /rentals/{id}/return: 200, статус Returned, сума не змінюється")
    void returnRental_returns200AndStatusReturned() {
        long rentalId = createRental(createEquipment(10000, 5), 1, 4, false);   // total = 40000

        given()
                .when().post("/rentals/{id}/return", rentalId)
                .then().statusCode(200)
                .body("id", equalTo((int) rentalId))
                .body("status", equalTo("Returned"))
                .body("total", equalTo(40000))
                .body("lateFee", equalTo(0));

        given()
                .when().get("/rentals/{id}", rentalId)
                .then().statusCode(200)
                .body("status", equalTo("Returned"));
    }

    // ---------- 5. Повторна або заборонена дія ----------

    @Test
    @DisplayName("Повторне повернення: 409, code RENTAL_CONFLICT і message")
    void returnTwice_returns409WithErrorContract() {
        long rentalId = createRental(createEquipment(10000, 5), 1, 4, false);
        given().when().post("/rentals/{id}/return", rentalId).then().statusCode(200);

        given()
                .when().post("/rentals/{id}/return", rentalId)
                .then().statusCode(409)
                .body("code", equalTo("RENTAL_CONFLICT"))
                .body("message", equalTo("Оренду вже повернено"));
    }

    @Test
    @DisplayName("Оренда більшої кількості, ніж є на складі: 409, code RENTAL_CONFLICT і message")
    void rentMoreThanStock_returns409WithErrorContract() {
        long equipmentId = createEquipment(10000, 2);

        given().contentType(JSON)
                .body(rentalBody(uniqueReference(), equipmentId, 3, 5, false))
                .when().post("/rentals")
                .then().statusCode(409)
                .body("code", equalTo("RENTAL_CONFLICT"))
                .body("message", equalTo("Недостатньо обладнання"));
    }

    @Test
    @DisplayName("Некоректна заявка (31 день): 400, code INVALID_REQUEST і message")
    void rentForTooManyDays_returns400WithErrorContract() {
        long equipmentId = createEquipment(10000, 5);

        given().contentType(JSON)
                .body(rentalBody(uniqueReference(), equipmentId, 1, 31, false))
                .when().post("/rentals")
                .then().statusCode(400)
                .body("code", equalTo("INVALID_REQUEST"))
                .body("message", equalTo("Некоректний запит"));
    }

    // ---------- 6. Незмінність після відхилення ----------

    @Test
    @DisplayName("Після 409 наступний GET показує ту саму оренду без змін")
    void afterRejectedReturn_rentalStaysUnchanged() {
        long rentalId = createRental(createEquipment(10000, 5), 1, 4, false);
        given().when().post("/rentals/{id}/return", rentalId).then().statusCode(200);

        String before = given().when().get("/rentals/{id}", rentalId)
                .then().statusCode(200)
                .extract().asString();

        given().when().post("/rentals/{id}/return", rentalId).then().statusCode(409);

        String after = given().when().get("/rentals/{id}", rentalId)
                .then().statusCode(200)
                .body("status", equalTo("Returned"))
                .extract().asString();

        assertEquals(before, after);
    }

    // ---------- 7. Персональне бізнес-правило (параметризація) ----------

    @ParameterizedTest(name = "{5}: {0} коп × {1} шт × {2} дн, premium={3} -> {4}")
    @CsvSource({
            "10000, 1,  9, false, 90000, без знижки (менше 10 днів)",
            "10000, 1, 10, false, 92000, 8% з 10-го дня включно",
            "10000, 1,  9, true,  79200, 12% преміальному клієнту",
            "10000, 1, 10, true,  88000, 12% без додавання 8%",
            "333,   1, 10, false, 3064,  знижка округлюється донизу"
    })
    @DisplayName("Персональне правило: 12% преміальному, інакше 8% від 10 днів")
    void personalRule_totalIsSavedWithCorrectDiscount(long dailyRate, int quantity, int days,
                                                      boolean premium, int expectedTotal, String description) {
        long equipmentId = createEquipment(dailyRate, 5);

        long rentalId = given().contentType(JSON)
                .body(rentalBody(uniqueReference(), equipmentId, quantity, days, premium))
                .when().post("/rentals")
                .then().statusCode(201)
                .body("total", equalTo(expectedTotal))
                .extract().jsonPath().getLong("id");

        given()
                .when().get("/rentals/{id}", rentalId)
                .then().statusCode(200)
                .body("total", equalTo(expectedTotal))
                .body("premium", equalTo(premium));
    }

    // ---------- 8. Workflow ----------

    @Test
    @DisplayName("Workflow: POST → GET → return → GET, id передається з попередньої відповіді")
    void workflow_createGetReturnGet() {
        long equipmentId = given().contentType(JSON)
                .body(Map.of("code", uniqueCode(), "dailyRate", 20000, "stock", 3))
                .when().post("/equipment")
                .then().statusCode(201)
                .extract().jsonPath().getLong("id");

        long rentalId = given().contentType(JSON)
                .body(rentalBody(uniqueReference(), equipmentId, 2, 12, false))
                .when().post("/rentals")
                .then().statusCode(201)
                .body("status", equalTo("Active"))
                .extract().jsonPath().getLong("id");

        given()
                .when().get("/rentals/{id}", rentalId)
                .then().statusCode(200)
                .body("status", equalTo("Active"))
                .body("item.equipmentId", equalTo((int) equipmentId))
                .body("total", equalTo(441600));      // 20000 × 2 × 12 = 480000, мінус 8%

        given()
                .when().post("/rentals/{id}/return", rentalId)
                .then().statusCode(200)
                .body("status", equalTo("Returned"));

        given()
                .when().get("/rentals/{id}", rentalId)
                .then().statusCode(200)
                .body("status", equalTo("Returned"))
                .body("total", equalTo(441600))
                .body("lateFee", equalTo(0));
    }
}
