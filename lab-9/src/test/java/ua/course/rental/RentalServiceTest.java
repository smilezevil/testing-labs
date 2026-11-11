package ua.course.rental;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class RentalServiceTest {

    private final RentalService service = new RentalService(
            mock(EquipmentRepository.class), mock(RentalRepository.class), (item, quantity, days) -> true);

    @ParameterizedTest(name = "{0} × {1} шт × {2} дн, premium={3} -> {4}")
    @CsvSource({
            "10000, 1,  9, false, 90000",
            "10000, 1, 10, false, 92000",
            "10000, 1,  9, true,  79200",
            "10000, 1, 10, true,  88000",
            "333,   1, 10, false, 3064"
    })
    @DisplayName("Персональне правило: 12% преміальному, інакше 8% від 10 днів")
    void calculateTotal_appliesPersonalRule(long dailyRate, int quantity, int days, boolean premium, long expected) {
        assertEquals(expected, service.calculateTotal(dailyRate, quantity, days, premium));
    }

    @Test
    @DisplayName("Кількість більше 20 відхиляється")
    void calculateTotal_rejectsTooManyItems() {
        assertThrows(IllegalArgumentException.class, () -> service.calculateTotal(10000, 21, 5, false));
    }
}