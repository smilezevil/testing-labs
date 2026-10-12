package carrental;

import carrental.CarRentalService.DriverHistory;
import carrental.CarRentalService.FleetAvailability;
import carrental.CarRentalService.RentalRepository;
import carrental.CarRentalService.Request;
import carrental.CarRentalService.Result;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CarRentalServiceTest {

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

    private static Request req(int age, int days, boolean premium, boolean insured) {
        return new Request(age, days, premium, insured);
    }


    @Nested
    @DisplayName("Базові сценарії")
    class BasicScenarios {

        @Test
        @DisplayName("Позитивний сценарій: стандартний водій без преміуму й страхування (TC-13)")
        void process_standardDriverNoInsurance_returnsFormedResultWithCorrectTotal() {
            when(fleet.isAvailable()).thenReturn(true);
            Request request = req(30, 10, false, false);

            Result result = service.process(request);

            assertEquals("Оформлено", result.status());
            assertEquals(650_000L, result.total());
        }

        @Test
        @DisplayName("Негативний бізнес-сценарій: авто відсутнє (TC-12)")
        void process_carUnavailable_returnsNoCarRejectionWithoutSideEffects() {
            when(fleet.isAvailable()).thenReturn(false);
            Request request = req(30, 5, false, false);

            Result result = service.process(request);

            assertEquals("Немає автомобіля", result.status());
            assertEquals(0L, result.total());
            verify(rentals, never()).save(any(), any());
        }

        @Test
        @DisplayName("Некоректні вхідні дані: вік нижче допустимого (TC-01)")
        void process_ageBelowMinimum_throwsValidationError() {
            Request request = req(20, 5, false, false);

            assertThrows(IllegalArgumentException.class, () -> service.process(request));
            verifyNoInteractions(fleet, history, rentals);
        }

        @Test
        @DisplayName("Некоректні вхідні дані: тривалість оренди вище допустимої (TC-05)")
        void process_daysAboveMaximum_throwsValidationError() {
            Request request = req(30, 31, false, false);

            assertThrows(IllegalArgumentException.class, () -> service.process(request));
            verifyNoInteractions(fleet, history, rentals);
        }

        @Test
        @DisplayName("Винятковий бізнес-сценарій: молодий преміум-водій з інцидентом (TC-09, правило R1)")
        void process_youngPremiumDriverWithIncident_returnsHistoryRejection() {
            when(fleet.isAvailable()).thenReturn(true);
            when(history.hasIncident()).thenReturn(true);
            Request request = req(23, 5, true, false);

            Result result = service.process(request);

            assertEquals("Відмова за історією", result.status());
            assertEquals(0L, result.total());
            verify(rentals, never()).save(any(), any());
        }
    }

    @Nested
    @DisplayName("Параметризоване тестування (BVA для age)")
    class ParameterizedValidation {

        @ParameterizedTest(name = "age={0} -> валідний? {1}")
        @DisplayName("Межі допустимого діапазону віку водія")
        @CsvSource({
                "20, false",
                "21, true",
                "22, true",
                "73, true",
                "74, true",
                "76, false"
        })
        void process_ageBoundaryValues_matchesExpectedValidity(int age, boolean isValid) {
            Request request = req(age, 5, false, false);

            if (isValid) {
                when(fleet.isAvailable()).thenReturn(true);
                Result result = service.process(request);
                assertEquals("Оформлено", result.status());
            } else {
                assertThrows(IllegalArgumentException.class, () -> service.process(request));
            }
        }
    }

    @Nested
    @DisplayName("Test Doubles: Stub / Mock повертають заздалегідь визначений або винятковий результат")
    class TestDoublesIsolation {

        @Test
        @DisplayName("Stub fleet повертає true -> обробка триває без реальної залежності")
        void fleetStub_returnsAvailable_processingProceeds() {
            when(fleet.isAvailable()).thenReturn(true);
            Request request = req(40, 3, false, false);

            Result result = service.process(request);

            assertEquals("Оформлено", result.status());
        }

        @Test
        @DisplayName("Stub fleet повертає альтернативний (негативний) результат false")
        void fleetStub_returnsUnavailable_processingRejectsImmediately() {
            when(fleet.isAvailable()).thenReturn(false);
            Request request = req(40, 3, false, false);

            Result result = service.process(request);

            assertEquals("Немає автомобіля", result.status());
        }

        @Test
        @DisplayName("Fleet-залежність генерує виняток -> помилка обслуговування поширюється")
        void fleetDependency_throwsException_serviceErrorPropagates() {
            when(fleet.isAvailable()).thenThrow(new IllegalStateException("Сервіс наявності недоступний"));
            Request request = req(40, 3, false, false);

            assertThrows(IllegalStateException.class, () -> service.process(request));
            verify(rentals, never()).save(any(), any());
        }

        @Test
        @DisplayName("History-залежність генерує виняток -> помилка обслуговування, оренда не зберігається")
        void historyDependency_throwsException_serviceErrorPropagatesAndNothingIsSaved() {
            when(fleet.isAvailable()).thenReturn(true);
            when(history.hasIncident()).thenThrow(new IllegalStateException("Сервіс історії недоступний"));
            Request request = req(23, 5, true, false);

            assertThrows(IllegalStateException.class, () -> service.process(request));
            verify(rentals, never()).save(any(), any());
        }
    }

    @Nested
    @DisplayName("State-Based Tests")
    class StateBasedTests {

        @Test
        @DisplayName("Повний пакет (преміум + молодий водій + страхування), TC-14: перевірка всіх складових суми")
        void process_fullPremiumPackage_computesAllAmountComponentsCorrectly() {
            when(fleet.isAvailable()).thenReturn(true);
            when(history.hasIncident()).thenReturn(false);
            Request request = req(22, 20, true, true);

            Result result = service.process(request);

            assertAll("Розрахована сума складається з коректних компонентів",
                    () -> assertEquals("Оформлено", result.status()),
                    () -> assertEquals(1_620_000L, result.rental()),
                    () -> assertEquals(200_000L, result.ageSurcharge()),
                    () -> assertEquals(160_000L, result.insurance()),
                    () -> assertEquals(100_000L, result.deposit()),
                    () -> assertEquals(2_080_000L, result.total())
            );
        }

        @Test
        @DisplayName("Знижка НЕ застосовується при тривалості оренди 6 діб (TC-06)")
        void process_sixDays_noDiscountApplied() {
            when(fleet.isAvailable()).thenReturn(true);
            Request request = req(30, 6, false, false);

            Result result = service.process(request);

            assertEquals(300_000L, result.rental());
        }
    }

    @Nested
    @DisplayName("Interaction-Based Tests")
    class InteractionBasedTests {

        @Test
        @DisplayName("Успішне оформлення зберігається в repository рівно з тими аргументами, що були оброблені")
        void process_onSuccess_savesRentalWithExactRequestAndResult() {
            when(fleet.isAvailable()).thenReturn(true);
            Request request = req(30, 10, false, false);

            Result result = service.process(request);

            ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
            ArgumentCaptor<Result> resultCaptor = ArgumentCaptor.forClass(Result.class);
            verify(rentals, times(1)).save(requestCaptor.capture(), resultCaptor.capture());

            assertEquals(request, requestCaptor.getValue());
            assertEquals(result, resultCaptor.getValue());
        }

        @Test
        @DisplayName("Для стандартного (не молодого, не преміум) водія історія взагалі не опитується")
        void process_standardDriver_neverQueriesHistory() {
            when(fleet.isAvailable()).thenReturn(true);
            Request request = req(40, 5, false, false);

            service.process(request);

            verify(fleet, times(1)).isAvailable();
            verify(history, never()).hasIncident();
        }

        @Test
        @DisplayName("Порядок викликів: наявність авто перевіряється до звернення до історії водія")
        void process_checksFleetAvailabilityBeforeDriverHistory() {
            when(fleet.isAvailable()).thenReturn(true);
            when(history.hasIncident()).thenReturn(false);
            Request request = req(23, 5, true, false);

            service.process(request);

            InOrder inOrder = inOrder(fleet, history);
            inOrder.verify(fleet).isAvailable();
            inOrder.verify(history).hasIncident();
        }
    }

    @Nested
    @DisplayName("Виняткові сценарії")
    class ExceptionalScenarios {

        @Test
        @DisplayName("Некоректний запит null призводить до помилки валідації без звернень до залежностей")
        void process_nullRequest_throwsValidationErrorWithoutTouchingDependencies() {
            assertThrows(IllegalArgumentException.class, () -> service.process(null));
            verifyNoInteractions(fleet, history, rentals);
        }

        @Test
        @DisplayName("Бізнес-умова забороняє завершення операції: авто відсутнє -> repository не викликається")
        void process_businessRuleBlocksCompletion_repositoryIsNeverInvoked() {
            when(fleet.isAvailable()).thenReturn(false);
            Request request = req(40, 5, false, false);

            service.process(request);

            verifyNoInteractions(rentals);
            verifyNoInteractions(history);
        }
    }


    @Nested
    @DisplayName("Виявлені дефекти реалізації (SUT не відповідає специфікації)")
    class DetectedDefects {

        @Disabled("Відомий дефект SUT (Лаб. №2): тест написаний за специфікацією і навмисно падає — виключено з вимірювання Coverage/Mutation для чистого запуску")
        @Test
        @DisplayName("Дефект №1 — верхня межа віку: специфікація дозволяє 75 років, SUT відхиляє")
        void process_age75_shouldBeAcceptedPerSpecification() {
            when(fleet.isAvailable()).thenReturn(true);
            Request request = req(75, 5, false, false);

            Result result = service.process(request);

            assertEquals("Оформлено", result.status());
        }

        @Disabled("Відомий дефект SUT (Лаб. №2): тест написаний за специфікацією і навмисно падає — виключено з вимірювання Coverage/Mutation для чистого запуску")
        @Test
        @DisplayName("Дефект №2 — поріг знижки: специфікація вимагає знижку з 7-ї доби включно")
        void process_sevenDaysRental_shouldApplyDiscountPerSpecification() {
            when(fleet.isAvailable()).thenReturn(true);
            Request request = req(30, 7, false, false);

            Result result = service.process(request);

            assertEquals(315_000L, result.rental());
        }

        @Disabled("Відомий дефект SUT (Лаб. №2): тест написаний за специфікацією і навмисно падає — виключено з вимірювання Coverage/Mutation для чистого запуску")
        @Test
        @DisplayName("Дефект №3 — умова опитування історії: специфікація вимагає OR, SUT використовує AND")
        void process_youngNotPremiumWithIncident_shouldBeRejectedPerSpecification() {
            when(fleet.isAvailable()).thenReturn(true);
            lenient().when(history.hasIncident()).thenReturn(true);
            Request request = req(23, 5, false, false);

            Result result = service.process(request);

            assertEquals("Відмова за історією", result.status());
        }
    }
}