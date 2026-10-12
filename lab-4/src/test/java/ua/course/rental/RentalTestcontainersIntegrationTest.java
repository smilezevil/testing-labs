package ua.course.rental;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;


@Testcontainers
class RentalTestcontainersIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("rental_variant04_test")
            .withUsername("rental_student")
            .withPassword("student")
            .withInitScript("migrations/V1__create_tables.sql");

    @Container
    static GenericContainer<?> wiremock = new GenericContainer<>(DockerImageName.parse("wiremock/wiremock:3.9.1"))
            .withExposedPorts(8080)
            .waitingFor(Wait.forListeningPort());

    private Connection connection;
    private EquipmentRepository equipmentRepo;
    private RentalRepository rentalRepo;
    private RentalService service;
    private Equipment equipment;

    @BeforeEach
    void setUp() throws SQLException {
        connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());

        try (var statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE rental_items, rentals, equipment RESTART IDENTITY CASCADE");
        }

        equipmentRepo = new EquipmentRepository(connection);
        rentalRepo = new RentalRepository(connection);
        equipment = equipmentRepo.create(new Equipment(0, "EQ-" + UUID.randomUUID(), 50_000, 10));

        configureFor(wiremock.getHost(), wiremock.getMappedPort(8080));
        reset();

        URI wiremockBaseUrl = URI.create("http://" + wiremock.getHost() + ":" + wiremock.getMappedPort(8080) + "/");
        RentalPolicy policy = new HttpRentalPolicy(wiremockBaseUrl, Duration.ofSeconds(5));
        service = new RentalService(equipmentRepo, rentalRepo, policy);
    }

    @AfterEach
    void tearDown() throws SQLException {
        connection.close();
    }

    private String reference() {
        return "REF-" + UUID.randomUUID();
    }

    @Test
    @DisplayName("Позитивний сценарій: WireMock дозволяє оренду -> запис реально створено в PostgreSQL, HTTP-запит виконано")
    void rent_wiremockAllows_createsActiveRentalAndCallsWiremock() throws SQLException {
        stubFor(post(urlEqualTo("/rental-policy"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"allowed\":true}")));

        RentalOrder result = service.rent(new RentalRequest(reference(), equipment.id(), 2, 5, false));

        assertEquals(RentalStatus.Active, result.status());
        assertEquals(500_000L, result.total());

        RentalOrder persisted = rentalRepo.findById(result.id());
        assertNotNull(persisted);
        assertEquals(RentalStatus.Active, persisted.status());
        assertEquals(500_000L, persisted.total());

        verify(1, postRequestedFor(urlEqualTo("/rental-policy"))
                .withRequestBody(matchingJsonPath("$.equipmentId", equalTo(String.valueOf(equipment.id()))))
                .withRequestBody(matchingJsonPath("$.quantity", equalTo("2")))
                .withRequestBody(matchingJsonPath("$.days", equalTo("5"))));
    }

    @Test
    @DisplayName("Персональне бізнес-правило: преміум-клієнт отримує знижку 12%, саме ця сума зберігається в БД")
    void rent_premiumCustomer_appliesDiscountAndPersistsCorrectTotal() throws SQLException {
        stubFor(post(urlEqualTo("/rental-policy"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"allowed\":true}")));

        RentalOrder result = service.rent(new RentalRequest(reference(), equipment.id(), 1, 5, true));

        assertEquals(220_000L, result.total());
        RentalOrder persisted = rentalRepo.findById(result.id());
        assertEquals(220_000L, persisted.total());
        assertTrue(persisted.premium());
    }

    @Test
    @DisplayName("Зовнішня бізнес-відмова: WireMock повертає allowed=false -> запис не створено, HTTP-запит був виконаний")
    void rent_wiremockRejects_noRentalCreatedButHttpCallHappened() throws SQLException {
        stubFor(post(urlEqualTo("/rental-policy"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"allowed\":false}")));

        assertThrows(RentalRejectedException.class,
                () -> service.rent(new RentalRequest(reference(), equipment.id(), 2, 5, false)));

        List<RentalOrder> active = rentalRepo.findRentals(equipment.id(), RentalStatus.Active);
        assertTrue(active.isEmpty());

        verify(1, postRequestedFor(urlEqualTo("/rental-policy")));
    }

    @Test
    @DisplayName("Недоступність зовнішнього сервісу (503): операція не завершується успішно, запис не створено, HTTP-запит був виконаний")
    void rent_wiremockUnavailable_throwsAndNoRentalCreated() throws SQLException {
        stubFor(post(urlEqualTo("/rental-policy"))
                .willReturn(aResponse().withStatus(503)));

        assertThrows(IllegalStateException.class,
                () -> service.rent(new RentalRequest(reference(), equipment.id(), 2, 5, false)));

        List<RentalOrder> active = rentalRepo.findRentals(equipment.id(), RentalStatus.Active);
        assertTrue(active.isEmpty());

        verify(1, postRequestedFor(urlEqualTo("/rental-policy")));
    }

    @Test
    @DisplayName("Локальна відмова: кількість перевищує залишок на складі -> відмова ДО звернення до зовнішнього сервісу, 0 HTTP-запитів")
    void rent_insufficientStock_localRejection_noHttpCallMade() throws SQLException {
        Equipment lowStock = equipmentRepo.create(new Equipment(0, "EQ-" + UUID.randomUUID(), 30_000, 3));

        assertThrows(RentalRejectedException.class,
                () -> service.rent(new RentalRequest(reference(), lowStock.id(), 5, 5, false)));

        List<RentalOrder> active = rentalRepo.findRentals(lowStock.id(), RentalStatus.Active);
        assertTrue(active.isEmpty());

        verify(0, postRequestedFor(urlEqualTo("/rental-policy")));
    }
}
