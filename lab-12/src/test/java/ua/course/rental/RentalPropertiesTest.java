package ua.course.rental;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.statistics.Statistics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Property-based тести на рівні Service + test doubles.
 * Репозиторії підмінені Mockito, політика — лямбдою: БД і HTTP не потрібні.
 */
class RentalPropertiesTest {

    private final RentalService service = new RentalService(
            mock(EquipmentRepository.class), mock(RentalRepository.class), (item, quantity, days) -> true);

    /** Одна заявка для розрахунку суми. */
    record RentalCase(long dailyRate, int quantity, int days, boolean premium) {
        long subtotal() { return dailyRate * quantity * days; }
    }

    // ================= custom generators =================

    /** Валідні заявки: усі межі (ціна 100 і 1 000 000, кількість 1 і 20, дні 1 і 30) + частіше межа правила 9/10 днів. */
    @Provide
    Arbitrary<RentalCase> validRentals() {
        Arbitrary<Long> dailyRates = Arbitraries.longs().between(100, 1_000_000);
        Arbitrary<Integer> quantities = Arbitraries.integers().between(1, 20);
        Arbitrary<Integer> days = Arbitraries.frequencyOf(
                Tuple.of(1, Arbitraries.of(9, 10)),
                Tuple.of(3, Arbitraries.integers().between(1, 30)));
        Arbitrary<Boolean> premium = Arbitraries.of(true, false);
        return Combinators.combine(dailyRates, quantities, days, premium).as(RentalCase::new);
    }

    /** Некоректні заявки: рівно одне поле виходить за межі (кількість, дні або ціна). */
    @Provide
    Arbitrary<RentalCase> invalidRentals() {
        Arbitrary<Long> goodRate = Arbitraries.longs().between(100, 1_000_000);
        Arbitrary<Integer> goodQuantity = Arbitraries.integers().between(1, 20);
        Arbitrary<Integer> goodDays = Arbitraries.integers().between(1, 30);
        Arbitrary<Boolean> premium = Arbitraries.of(true, false);

        Arbitrary<Integer> badQuantity = Arbitraries.oneOf(
                Arbitraries.integers().lessOrEqual(0), Arbitraries.integers().greaterOrEqual(21));
        Arbitrary<Integer> badDays = Arbitraries.oneOf(
                Arbitraries.integers().lessOrEqual(0), Arbitraries.integers().greaterOrEqual(31));
        Arbitrary<Long> badRate = Arbitraries.oneOf(
                Arbitraries.longs().lessOrEqual(99), Arbitraries.longs().greaterOrEqual(1_000_001));

        return Arbitraries.oneOf(
                Combinators.combine(goodRate, badQuantity, goodDays, premium).as(RentalCase::new),
                Combinators.combine(goodRate, goodQuantity, badDays, premium).as(RentalCase::new),
                Combinators.combine(badRate, goodQuantity, goodDays, premium).as(RentalCase::new));
    }

    // ================= properties =================

    @Property(tries = 1000)
    @Label("1. Персональне правило: 12% преміальному, інакше 8% від 10 днів, знижки не додаються")
    void personalRule(@ForAll("validRentals") RentalCase c) {
        long total = service.calculateTotal(c.dailyRate(), c.quantity(), c.days(), c.premium());
        long subtotal = c.subtotal();

        String type;
        long discount;
        if (c.premium()) {
            type = "преміальний: -12%";
            discount = subtotal * 12 / 100;
        } else if (c.days() >= 10) {
            type = "звичайний, 10+ днів: -8%";
            discount = subtotal * 8 / 100;
        } else {
            type = "звичайний, до 9 днів: без знижки";
            discount = 0;
        }
        Statistics.label("випадок правила").collect(type);
        Statistics.label("випадок правила").coverage(coverage -> {
            coverage.check("преміальний: -12%").percentage(p -> p > 20);
            coverage.check("звичайний, 10+ днів: -8%").percentage(p -> p > 10);
            coverage.check("звичайний, до 9 днів: без знижки").percentage(p -> p > 10);
        });

        assertEquals(subtotal - discount, total);
    }

    @Property(tries = 1000)
    @Label("2. Сума >= 0 і знижка ніколи не збільшує суму")
    void totalIsNonNegativeAndNotGreaterThanSubtotal(
            @ForAll @LongRange(min = 100, max = 1_000_000) long dailyRate,
            @ForAll @IntRange(min = 1, max = 20) int quantity,
            @ForAll @IntRange(min = 1, max = 30) int days,
            @ForAll boolean premium) {
        long total = service.calculateTotal(dailyRate, quantity, days, premium);

        assertTrue(total >= 0, "сума від'ємна: " + total);
        assertTrue(total <= dailyRate * quantity * days, "знижка збільшила суму: " + total);
    }

    @Property(tries = 1000)
    @Label("3. Преміальний клієнт ніколи не платить більше за звичайного")
    void premiumNeverPaysMore(@ForAll("validRentals") RentalCase c) {
        long premiumTotal = service.calculateTotal(c.dailyRate(), c.quantity(), c.days(), true);
        long regularTotal = service.calculateTotal(c.dailyRate(), c.quantity(), c.days(), false);

        assertTrue(premiumTotal <= regularTotal, premiumTotal + " > " + regularTotal);
    }

    @Property(tries = 1000)
    @Label("4. Кількість, тривалість або ціна поза межами завжди відхиляються")
    void invalidInputIsRejected(@ForAll("invalidRentals") RentalCase c) {
        assertThrows(IllegalArgumentException.class,
                () -> service.calculateTotal(c.dailyRate(), c.quantity(), c.days(), c.premium()));
    }

    // ================= deterministic boundary tests (межі, які генерація перевіряє як edge cases) =================

    @Example
    @Label("Boundary: звичайний клієнт, 9 днів -> без знижки, 10 днів -> -8%")
    void boundaryRegularNineAndTenDays() {
        assertEquals(90_000, service.calculateTotal(10_000, 1, 9, false));
        assertEquals(92_000, service.calculateTotal(10_000, 1, 10, false));
    }

    @Example
    @Label("Boundary: преміальний клієнт, 10 днів -> -12%, а не -20% (знижки не додаються)")
    void boundaryPremiumDiscountsDoNotStack() {
        assertEquals(88_000, service.calculateTotal(10_000, 1, 10, true));
    }

    @Example
    @Label("Boundary: мінімальна і максимальна сума, округлення донизу")
    void boundaryMinMaxAndRounding() {
        assertEquals(100, service.calculateTotal(100, 1, 1, false));
        assertEquals(88, service.calculateTotal(100, 1, 1, true));
        assertEquals(528_000_000, service.calculateTotal(1_000_000, 20, 30, true));
        assertEquals(3_064, service.calculateTotal(333, 1, 10, false));   // 3330 - 266,4 -> 3330 - 266
    }
}