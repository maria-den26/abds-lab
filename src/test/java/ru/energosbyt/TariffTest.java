package ru.energosbyt;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Проверки различия между некорректной датой и отсутствием действующего тарифа. */
class TariffTest {
    private final ClickHouse ch = mock(ClickHouse.class);
    private final EnergosbytService service = new EnergosbytService(ch);

    /** Корректная дата вне диапазона DateTime не может принадлежать версии тарифа. */
    @Test
    void datesOutsideStorageRangeHaveNoTariff() {
        for (String date : new String[]{"1900-01-01", "1969-12-31", "2107-01-01", "9999-12-31"}) {
            ApiException error = assertThrows(ApiException.class, () -> service.tariff(date));
            assertEquals(404, error.status(), date);
            assertEquals("Not found", error.getMessage());
        }
        verifyNoInteractions(ch);
    }

    /** Ошибки формата и календаря остаются ошибками запроса, независимо от наличия данных. */
    @Test
    void invalidDatesRemainBadRequests() {
        for (String date : new String[]{null, "", "not-a-date", "2026-02-30", "1900-02-29"}) {
            ApiException error = assertThrows(ApiException.class, () -> service.tariff(date));
            assertEquals(400, error.status());
        }
        verifyNoInteractions(ch);
    }

    /** Отсутствие версии в допустимом диапазоне определяется результатом запроса к БД. */
    @Test
    void emptyHistoryReturnsNotFound() {
        when(ch.rows(anyString(), anyMap())).thenReturn(new ObjectMapper().createArrayNode());
        assertEquals(404, assertThrows(ApiException.class, () -> service.tariff("2023-10-31")).status());
    }
}
