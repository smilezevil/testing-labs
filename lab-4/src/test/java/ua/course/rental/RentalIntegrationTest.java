package ua.course.rental;

import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RentalIntegrationTest {

    private Connection connection;
    private EquipmentRepository equipment;
    private RentalRepository rentals;

    private Long createdEquipmentId;
    private Long createdRentalId;

    @BeforeEach
    void setUp() throws SQLException {
        connection = Database.connect();
        equipment = new EquipmentRepository(connection);
        rentals = new RentalRepository(connection);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (createdRentalId != null) {
            rentals.delete(createdRentalId);
        }
        if (createdEquipmentId != null) {
            equipment.delete(createdEquipmentId);
        }
        connection.close();
    }

    private Equipment newEquipment(long dailyRate, int stock) throws SQLException {
        String code = "IT-" + UUID.randomUUID();
        Equipment saved = equipment.create(new Equipment(0, code, dailyRate, stock));
        createdEquipmentId = saved.id();
        return saved;
    }

    private String uniqueReference() {
        return "REF-" + UUID.randomUUID();
    }

    //Repo-Database test
    @Test
    @DisplayName("Equipment: create() та findById() повертають однаково збережені дані")
    void equipmentRepository_createThenFindById_repositoryState() throws SQLException {
        Equipment saved = newEquipment(15_000, 10);

        Equipment fetched = equipment.findById(saved.id());

        assertNotNull(fetched);
        assertEquals(saved.code(), fetched.code());
        assertEquals(15_000, fetched.dailyRate());
        assertEquals(10, fetched.stock());
    }

    @Test
    @DisplayName("Rental: create() та findById() повертають оренду разом з її позицією")
    void rentalRepository_createThenFindById_repositoryState() throws SQLException {
        Equipment item = newEquipment(20_000, 5);
        String reference = uniqueReference();
        RentalOrder toSave = new RentalOrder(0, reference, 3, false, 60_000, 0,
                RentalStatus.Active, new RentalItem(0, item.id(), 2, item.dailyRate()));

        RentalOrder saved = rentals.create(toSave);
        createdRentalId = saved.id();

        RentalOrder fetched = rentals.findById(saved.id());

        assertNotNull(fetched);
        assertEquals(reference, fetched.reference());
        assertEquals(RentalStatus.Active, fetched.status());
        assertEquals(2, fetched.item().quantity());
        assertEquals(item.id(), fetched.item().equipmentId());
    }

    @Test
    @DisplayName("Rental: markReturned() — операція зміни; новий статус дійсно зберігається в БД")
    void rentalRepository_markReturned_updatesPersistedStatus() throws SQLException {
        Equipment item = newEquipment(10_000, 5);
        RentalOrder saved = rentals.create(new RentalOrder(0, uniqueReference(), 2, false,
                20_000, 0, RentalStatus.Active, new RentalItem(0, item.id(), 1, item.dailyRate())));
        createdRentalId = saved.id();

        rentals.markReturned(saved.id(), 0);
        RentalOrder reloaded = rentals.findById(saved.id());

        assertEquals(RentalStatus.Returned, reloaded.status());
        assertEquals(0, reloaded.lateFee());
    }

    @Test
    @DisplayName("Схема БД: обладнання з активною позицією оренди видалити не можна (FK RESTRICT)")
    void equipmentRepository_deleteWithActiveRentalItems_violatesForeignKey() throws SQLException {
        Equipment item = newEquipment(10_000, 5);
        RentalOrder saved = rentals.create(new RentalOrder(0, uniqueReference(), 1, false,
                10_000, 0, RentalStatus.Active, new RentalItem(0, item.id(), 1, item.dailyRate())));
        createdRentalId = saved.id();

        assertThrows(SQLException.class, () -> equipment.delete(item.id()));
    }

    //service-repo-database test and positive
    @Test
    @DisplayName("Позитивний сценарій: дозволена політика -> оренда оформлена зі статусом Active")
    void rent_withAllowedPolicyAndSufficientStock_createsActiveRental_serviceState() throws SQLException {
        Equipment item = newEquipment(50_000, 10);
        RentalPolicy policy = mock(RentalPolicy.class);
        when(policy.isAllowed(any(Equipment.class), anyInt(), anyInt())).thenReturn(true);
        RentalService service = new RentalService(equipment, rentals, policy);

        RentalOrder result = service.rent(new RentalRequest(uniqueReference(), item.id(), 2, 5, false));
        createdRentalId = result.id();

        assertEquals(RentalStatus.Active, result.status());
        assertEquals(500_000L, result.total());
        verify(policy).isAllowed(eq(item), eq(2), eq(5));
    }

    //negative
    @Test
    @DisplayName("Негативний сценарій: політика відхиляє -> виняток і жодного запису в БД")
    void rent_whenPolicyRejects_noRentalIsCreated_serviceState() throws SQLException {
        Equipment item = newEquipment(50_000, 10);
        RentalPolicy policy = (eq, qty, days) -> false; // лямбда-стаб: завжди відмова
        RentalService service = new RentalService(equipment, rentals, policy);

        assertThrows(RentalRejectedException.class,
                () -> service.rent(new RentalRequest(uniqueReference(), item.id(), 2, 5, false)));

        List<RentalOrder> activeForItem = rentals.findRentals(item.id(), RentalStatus.Active);
        assertTrue(activeForItem.isEmpty());
    }

    //business-rule
    @Test
    @DisplayName("Персональне правило: преміум-клієнт отримує 12%, і саме ця сума зберігається в БД")
    void rent_premiumCustomer_persistedTotalReflectsDiscount_persistedState() throws SQLException {
        Equipment item = newEquipment(100_000, 10);
        RentalPolicy policy = (eq, qty, days) -> true;
        RentalService service = new RentalService(equipment, rentals, policy);

        RentalOrder result = service.rent(new RentalRequest(uniqueReference(), item.id(), 1, 5, true));
        createdRentalId = result.id();

        assertEquals(440_000L, result.total());

        RentalOrder persisted = rentals.findById(result.id());
        assertEquals(440_000L, persisted.total());
        assertTrue(persisted.premium());
    }

    @Test
    @DisplayName("Персональне правило: без преміуму знижка 8% діє лише з 10-ї доби включно (межа)")
    void rent_nonPremiumNineVsTenDays_eightPercentDiscountAppliesFromTenthDayOnly() throws SQLException {
        Equipment item = newEquipment(10_000, 10);
        RentalPolicy policy = (eq, qty, days) -> true;
        RentalService service = new RentalService(equipment, rentals, policy);

        RentalOrder nineDays = service.rent(new RentalRequest(uniqueReference(), item.id(), 1, 9, false));
        assertEquals(90_000L, nineDays.total());
        rentals.delete(nineDays.id());

        RentalOrder tenDays = service.rent(new RentalRequest(uniqueReference(), item.id(), 1, 10, false));
        createdRentalId = tenDays.id();
        assertEquals(92_000L, tenDays.total());
    }

    @Test
    @DisplayName("Негативний сценарій: недостатній залишок -> відмова без створення запису")
    void rent_whenQuantityExceedsStock_rejectsWithoutCreatingRecord() throws SQLException {
        Equipment item = newEquipment(10_000, 2);
        RentalPolicy policy = (eq, qty, days) -> true;
        RentalService service = new RentalService(equipment, rentals, policy);

        assertThrows(RentalRejectedException.class,
                () -> service.rent(new RentalRequest(uniqueReference(), item.id(), 5, 3, false)));

        List<RentalOrder> activeForItem = rentals.findRentals(item.id(), RentalStatus.Active);
        assertTrue(activeForItem.isEmpty());
    }

    @Test
    @DisplayName("Повернення: статус Active -> Returned, сума та залишок обладнання не змінюються")
    void returnRental_activeRental_marksReturnedAndKeepsTotalAndStock() throws SQLException {
        Equipment item = newEquipment(10_000, 7);
        RentalPolicy policy = (eq, qty, days) -> true;
        RentalService service = new RentalService(equipment, rentals, policy);
        RentalOrder created = service.rent(new RentalRequest(uniqueReference(), item.id(), 3, 4, false));
        createdRentalId = created.id();

        RentalOrder returned = service.returnRental(created.id());

        assertEquals(RentalStatus.Returned, returned.status());
        assertEquals(created.total(), returned.total());
        assertEquals(0, returned.lateFee());

        Equipment itemAfter = equipment.findById(item.id());
        assertEquals(7, itemAfter.stock());
    }

    @Test
    @DisplayName("Негативний сценарій: повторне повернення вже поверненої оренди відхиляється")
    void returnRental_alreadyReturned_throwsAndStateStaysUnchanged() throws SQLException {
        Equipment item = newEquipment(10_000, 5);
        RentalPolicy policy = (eq, qty, days) -> true;
        RentalService service = new RentalService(equipment, rentals, policy);
        RentalOrder created = service.rent(new RentalRequest(uniqueReference(), item.id(), 1, 2, false));
        createdRentalId = created.id();
        service.returnRental(created.id());

        assertThrows(RentalRejectedException.class, () -> service.returnRental(created.id()));

        RentalOrder stillReturned = rentals.findById(created.id());
        assertEquals(RentalStatus.Returned, stillReturned.status());
        assertEquals(0, stillReturned.lateFee());
    }
}
