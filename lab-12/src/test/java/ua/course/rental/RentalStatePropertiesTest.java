package ua.course.rental;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeContainer;
import net.jqwik.api.lifecycle.BeforeTry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based тести для persisted state: Service + реальна test DB (PostgreSQL у Testcontainers).
 * Перед кожною спробою (і кожною спробою shrinking) БД очищується, тому cases незалежні.
 */
class RentalStatePropertiesTest {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"))
            .withInitScript("migrations/V1__create_tables.sql");

    private Connection connection;
    private EquipmentRepository equipment;
    private RentalRepository rentals;
    private RentalService service;

    @BeforeContainer
    static void startDatabase() {
        postgres.start();
    }

    @AfterContainer
    static void stopDatabase() {
        postgres.stop();
    }

    @BeforeTry
    void cleanDatabase() throws SQLException {
        connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        try (var statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE rental_items, rentals, equipment RESTART IDENTITY CASCADE");
        }
        equipment = new EquipmentRepository(connection);
        rentals = new RentalRepository(connection);
        service = new RentalService(equipment, rentals, (item, quantity, days) -> true);
    }

    @AfterTry
    void closeConnection() throws SQLException {
        connection.close();
    }

    /** Залишок і кількість, яка гарантовано більша за залишок. */
    record StockCase(int stock, int quantity) {}

    // custom generator: спочатку залишок, потім кількість строго більша за нього
    @Provide
    Arbitrary<StockCase> quantityAboveStock() {
        return Arbitraries.integers().between(0, 19)
                .flatMap(stock -> Arbitraries.integers().between(stock + 1, 20)
                        .map(quantity -> new StockCase(stock, quantity)));
    }

    @Property(tries = 200)
    @Label("5. Заборонена оренда (кількість > залишку) відхиляється і не змінює стан БД")
    void rejectedRentalDoesNotChangeState(@ForAll("quantityAboveStock") StockCase c,
                                          @ForAll @IntRange(min = 1, max = 30) int days,
                                          @ForAll boolean premium) throws SQLException {
        Equipment item = equipment.create(new Equipment(0, "EQ-1", 10_000, c.stock()));

        assertThrows(RentalRejectedException.class,
                () -> service.rent(new RentalRequest("RENT-1", item.id(), c.quantity(), days, premium)));

        assertEquals(c.stock(), equipment.findById(item.id()).stock(), "залишок змінився");
        assertEquals(0, count("rentals"), "з'явився запис оренди");
        assertEquals(0, count("rental_items"), "з'явилась позиція оренди");
    }

    @Property(tries = 200)
    @Label("6. Збережена оренда має суму за правилом; повторне повернення відхиляється і не змінює стан")
    void persistedRentalLifecycle(@ForAll @LongRange(min = 100, max = 1_000_000) long dailyRate,
                                  @ForAll @IntRange(min = 1, max = 20) int quantity,
                                  @ForAll @IntRange(min = 1, max = 30) int days,
                                  @ForAll boolean premium) throws SQLException {
        Equipment item = equipment.create(new Equipment(0, "EQ-1", dailyRate, 20));
        RentalOrder saved = service.rent(new RentalRequest("RENT-1", item.id(), quantity, days, premium));

        RentalOrder stored = rentals.findById(saved.id());
        assertEquals(RentalStatus.Active, stored.status());
        assertEquals(service.calculateTotal(dailyRate, quantity, days, premium), stored.total());
        assertEquals(dailyRate, stored.item().dailyRate(), "знімок ціни не збережено");
        assertEquals(20, equipment.findById(item.id()).stock(), "оформлення змінило залишок");

        service.returnRental(saved.id());
        assertThrows(RentalRejectedException.class, () -> service.returnRental(saved.id()));

        RentalOrder afterSecondReturn = rentals.findById(saved.id());
        assertEquals(RentalStatus.Returned, afterSecondReturn.status());
        assertEquals(stored.total(), afterSecondReturn.total());
    }

    private long count(String table) throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT count(*) FROM " + table)) {
            result.next();
            return result.getLong(1);
        }
    }
}