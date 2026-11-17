package ru.energosbyt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Проверки воспроизводимости данных, расчёта счетов и замены счётчика.
 */
class DataGeneratorTest {
    @TempDir Path directory;
    /**
     * Проверяет количество записей, наличие корректировок, стоимость и сроки оплаты счетов; повторная
     * генерация сравнивается побайтово.
     */
    @Test
    void generatesReproducibleRelationsCorrectionsAndMonthlyInvoices() throws Exception {
        DataGenerator.generate(directory, 10, LocalDate.of(2023, 11, 1), LocalDate.of(2023, 12, 31));
        assertEquals(10, Files.readAllLines(directory.resolve("dim_realty.csv")).size());
        assertEquals(11, Files.readAllLines(directory.resolve("dim_gauges.csv")).size());
        assertEquals(12, Files.readAllLines(directory.resolve("fact_gauge_bindings.csv")).size());
        List<String> readings = Files.readAllLines(directory.resolve("fact_meter_readings.csv"));
        assertTrue(readings.size() > 610);
        List<String> invoices = Files.readAllLines(directory.resolve("fact_invoice.csv"));
        assertEquals(20, invoices.size());
        for (String line : invoices) {
            String[] values = line.split(",");
            long expected = Long.parseLong(values[4]) * 500 + Long.parseLong(values[5]) * 250;
            assertEquals(expected, Long.parseLong(values[6]));
            assertTrue(values[7].equals("\"2023-12-10\"") || values[7].equals("\"2024-01-10\""));
        }
        DataGenerator.generate(directory.resolve("again"), 10, LocalDate.of(2023, 11, 1), LocalDate.of(2023, 12, 31));
        for (String table : DataTools.TABLES)
            assertEquals(-1, Files.mismatch(directory.resolve(table + ".csv"), directory.resolve("again").resolve(table + ".csv")));
    }
    /**
     * Проверяет малое начальное показание нового счётчика и расчёт стоимости счетов по тарифу периода.
     */
    @Test
    void replacementResetsCumulativeReadingButNotConsumption() throws Exception {
        DataGenerator.generate(directory, 10, LocalDate.of(2025, 4, 1), LocalDate.of(2025, 5, 31));
        List<String> rows = Files.readAllLines(directory.resolve("fact_meter_readings.csv"));
        String firstMay = rows.stream().filter(s -> s.startsWith("1,1,1,11,\"2025-05-01\"")).reduce((a,b) -> b).orElseThrow();
        assertTrue(Integer.parseInt(firstMay.split(",")[5]) <= 15);
        for (String line : Files.readAllLines(directory.resolve("fact_invoice.csv"))) {
            String[] v = line.split(",");
            assertEquals(Long.parseLong(v[4]) * 620 + Long.parseLong(v[5]) * 310, Long.parseLong(v[6]));
        }
    }
}
