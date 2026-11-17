package ru.energosbyt;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Проверки диапазонов чисел, форматов дат и параметров пагинации.
 */
class InputTest {
    private final ObjectMapper json = new ObjectMapper();
    /**
     * Проверяет типы JSON, переполнение и границу UInt32.
     */
    @Test
    void rejectsFractionsStringsBooleansNegativesAndOverflow() throws Exception {
        for (String value : new String[]{"1.5", "\"1\"", "true", "-1", "4294967296", "null"}) {
            var body = json.readTree("{\"day_kw\":" + value + "}");
            assertThrows(ApiException.class, () -> Input.number(body, "day_kw", false), value);
        }
        assertEquals("4294967295", Input.number(json.readTree("{\"day_kw\":4294967295}"), "day_kw", false).toString());
    }
    /**
     * Проверяет допустимый максимум UInt64, переполнение и отклонение строки с недесятичными символами.
     */
    @Test
    void acceptsFullUInt64Identifiers() throws Exception {
        assertEquals("18446744073709551615", Input.id("18446744073709551615"));
        assertThrows(ApiException.class, () -> Input.id("18446744073709551616"));
        assertThrows(ApiException.class, () -> Input.id("1 OR 1=1"));
    }
    /**
     * Проверяет некорректные календарные даты и значения пагинации.
     */
    @Test
    void rejectsInvalidCalendarDatesAndPagination() {
        for (String date : new String[]{"2026-02-29", "2026-13-01", "2026-1-01", "", "2026-10-01x"})
            assertThrows(ApiException.class, () -> Input.date(date, "date", true));
        for (String value : new String[]{"-1", "1.5", "abc", "9223372036854775808"})
            assertThrows(ApiException.class, () -> Input.page(value, "limit"));
        assertEquals(0, Input.page("0", "limit"));
    }
}
