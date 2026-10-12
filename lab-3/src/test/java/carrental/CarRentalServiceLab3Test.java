package carrental;

import carrental.CarRentalService.DriverHistory;
import carrental.CarRentalService.FleetAvailability;
import carrental.CarRentalService.RentalRepository;
import carrental.CarRentalService.Request;
import carrental.CarRentalService.Result;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CarRentalServiceLab3Test {

    @Mock
    private FleetAvailability fleet;
    @Mock
    private DriverHistory history;
    @Mock
    private RentalRepository rentals;

    private CarRentalService service;

    @BeforeEach
    void setUp() {
        service = new CarRentalService(fleet, history, rentals);
    }

    @ParameterizedTest(name = "age={0}, days=5 -> ageSurcharge={1}")
    @DisplayName("Вікова доплата застосовується лише до водіїв молодше 25 років")
    @CsvSource({
            "21, 50000",
            "24, 50000",
            "25, 0",
            "26, 0",
            "40, 0"
    })
    void process_ageSurchargeThreshold_appliesOnlyBelow25(int age, long expectedSurcharge) {
        when(fleet.isAvailable()).thenReturn(true);
        Request request = new Request(age, 5, false, false);

        Result result = service.process(request);

        assertEquals(expectedSurcharge, result.ageSurcharge());
        assertEquals(450_000L + expectedSurcharge, result.total());
    }

    @ParameterizedTest(name = "days={0} -> rental={1}")
    @DisplayName("Орендна плата на межах діапазону та навколо порогу знижки")
    @CsvSource({
            "1, 50000",
            "6, 300000",
            "8, 360000",
            "30, 1350000"
    })
    void process_rentalAmountAroundDiscountThreshold_isCalculatedCorrectly(int days, long expectedRental) {
        when(fleet.isAvailable()).thenReturn(true);
        Request request = new Request(30, days, false, false);

        Result result = service.process(request);

        assertEquals("Оформлено", result.status());
        assertEquals(expectedRental, result.rental());
    }

    @Test
    @DisplayName("Некоректні вхідні дані: тривалість оренди 0 діб")
    void process_daysBelowMinimum_throwsValidationError() {
        Request request = new Request(30, 0, false, false);

        assertThrows(IllegalArgumentException.class, () -> service.process(request));
        verifyNoInteractions(fleet, history, rentals);
    }

    @Test
    @DisplayName("Конструктор відхиляє null-залежності з чітким повідомленням")
    void constructor_nullDependency_throwsNullPointerWithMessage() {
        assertAll("Кожна залежність обов'язкова",
                () -> assertEquals("Залежність не задано: fleet",
                        assertThrows(NullPointerException.class,
                                () -> new CarRentalService(null, history, rentals)).getMessage()),
                () -> assertEquals("Залежність не задано: history",
                        assertThrows(NullPointerException.class,
                                () -> new CarRentalService(fleet, null, rentals)).getMessage()),
                () -> assertEquals("Залежність не задано: rentals",
                        assertThrows(NullPointerException.class,
                                () -> new CarRentalService(fleet, history, null)).getMessage())
        );
    }

    @Disabled("Відомий дефект SUT (Лаб. №2): тест написаний за специфікацією і навмисно падає — виключено з вимірювання Coverage/Mutation для чистого запуску")
    @Test
    @DisplayName("Дефект №3 (варіант R5): преміум-водій 25+ з інцидентом — специфікація вимагає відмову")
    void process_notYoungPremiumWithIncident_shouldBeRejectedPerSpecification() {
        when(fleet.isAvailable()).thenReturn(true);
        lenient().when(history.hasIncident()).thenReturn(true);
        Request request = new Request(30, 5, true, false);

        Result result = service.process(request);

        assertEquals("Відмова за історією", result.status());
    }
}