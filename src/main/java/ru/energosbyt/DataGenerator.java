package ru.energosbyt;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/**
 * Генерирует воспроизводимые CSV с накопительными показаниями и месячными счетами. Строки записываются сразу
 * в файлы, без хранения полного набора в памяти.
 */
public final class DataGenerator {
    /** Первая дата показаний полного датасета включительно. */
    public static final LocalDate START = LocalDate.of(2023, 11, 1);
    /** Последняя дата показаний полного датасета включительно. */
    public static final LocalDate END = LocalDate.of(2026, 10, 31);
    private static final LocalDate REPLACEMENT = LocalDate.of(2025, 5, 1);
    private DataGenerator() { }

    /**
     * Перезаписывает CSV для 1 000 объектов за период START–END, включая корректировки и замены счётчиков.
     *
     * @param directory каталог выходных CSV
     * @throws IOException при ошибке записи файлов
     */
    public static void generate(Path directory) throws IOException { generate(directory, 1000, START, END); }

    /**
     * Создаёт CSV с заданным числом объектов и периодом для тестов. Правила тарифов и замены счётчиков
     * совпадают с полной генерацией.
     *
     * @param directory каталог выходных CSV
     * @param objects число объектов
     * @param start первая дата включительно
     * @param end последняя дата включительно
     * @throws IOException при ошибке записи файлов
     */
    static void generate(Path directory, int objects, LocalDate start, LocalDate end) throws IOException {
        Files.createDirectories(directory);
        try (Csv realty = new Csv(directory, "dim_realty"); Csv gauges = new Csv(directory, "dim_gauges");
             Csv tariffs = new Csv(directory, "dim_tariffs"); Csv users = new Csv(directory, "dim_users");
             Csv bindings = new Csv(directory, "fact_gauge_bindings"); Csv readings = new Csv(directory, "fact_meter_readings");
             Csv invoices = new Csv(directory, "fact_invoice")) {
            String born = START + " 00:00:00";
            tariffs.row(1, "День–Ночь", 500, 250, born, "2024-11-01 00:00:00", born, born);
            tariffs.row(1, "День–Ночь", 620, 310, "2024-11-01 00:00:00", "2026-11-01 00:00:00", "2024-11-01 00:00:00", "2024-11-01 00:00:00");
            tariffs.row(1, "День–Ночь", 700, 350, "2026-11-01 00:00:00", "2099-01-01 00:00:00", "2026-11-01 00:00:00", "2026-11-01 00:00:00");
            int replacements = objects / 10;
            for (int id = 1; id <= objects; id++) {
                boolean flat = id % 10 != 0;
                realty.row(id, "г. Москва, ул. Энергетиков, дом " + ((id - 1) / 100 + 1), 30 + id % 100,
                        flat ? "flat" : "nrcp", flat ? String.valueOf((id - 1) % 100 + 1) : "", flat ? 1 + id % 4 : 0,
                        1 + id % 16, 1 + id % 4, born);
                gauges.row(id, "Меркурий 200", "Россия", born);
                users.row(id, "Собственник " + id, String.format("+7900%07d", id), "", born, born);
                bindings.row(id, id, born, 1, 1);
                if (id <= replacements) {
                    gauges.row(objects + id, "Меркурий 230", "Россия", REPLACEMENT + " 00:00:00");
                    bindings.row(id, id, born, -1, 1);
                    bindings.row(objects + id, id, REPLACEMENT + " 00:00:00", 1, 2);
                }
                int day = 0, night = 0, monthDay = 0, monthNight = 0;
                for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
                    long index = ChronoUnit.DAYS.between(START, date);
                    if (id <= replacements && date.equals(REPLACEMENT)) { day = 0; night = 0; }
                    int dailyDay = 8 + (int) ((id + index) % 8);
                    int dailyNight = 3 + (int) ((id + index) % 5);
                    day += dailyDay; night += dailyNight;
                    monthDay += dailyDay; monthNight += dailyNight;
                    int gauge = id <= replacements && !date.isBefore(REPLACEMENT) ? objects + id : id;
                    boolean corrected = (id + index) % 100 < 3;
                    readings.row(id, id, 1, gauge, date, corrected ? day - 1 : day, night, date + " 12:00:00");
                    if (corrected) readings.row(id, id, 1, gauge, date, day, night, date + " 12:00:01");
                    if (date.getDayOfMonth() == date.lengthOfMonth()) {
                        int dayPrice = date.isBefore(LocalDate.of(2024, 11, 1)) ? 500 : 620;
                        int nightPrice = dayPrice / 2;
                        long price = (long) monthDay * dayPrice + (long) monthNight * nightPrice;
                        invoices.row(id, id, 1, gauge, monthDay, monthNight, price,
                                YearMonth.from(date).plusMonths(1).atDay(10), date + " 23:00:00");
                        monthDay = 0; monthNight = 0;
                    }
                }
            }
            System.out.printf("Generated %d objects, %d days, %d readings (%d corrections), %d invoices%n",
                    objects, ChronoUnit.DAYS.between(start, end) + 1, readings.count, readings.count - objects * (ChronoUnit.DAYS.between(start, end) + 1), invoices.count);
        }
    }

    /**
     * Записывает CSV в UTF-8 без заголовков: числа без кавычек, остальные значения в кавычках с удвоением
     * внутренних кавычек.
     */
    private static final class Csv implements AutoCloseable {
        private final BufferedWriter writer;
        private long count;
        /**
         * Открывает буферизированную запись таблицы в UTF-8.
         *
         * @param directory каталог CSV
         * @param name имя таблицы
         * @throws IOException если файл нельзя открыть
         */
        Csv(Path directory, String name) throws IOException {
            writer = new BufferedWriter(Files.newBufferedWriter(directory.resolve(name + ".csv"), StandardCharsets.UTF_8), 1 << 20);
        }
        /**
         * Записывает строку с экранированием кавычек и разделителей.
         *
         * @param values значения колонок в порядке схемы
         * @throws IOException при ошибке записи
         */
        void row(Object... values) throws IOException {
            for (int i = 0; i < values.length; i++) {
                if (i > 0) writer.write(',');
                if (values[i] instanceof Number) writer.write(values[i].toString());
                else {
                    writer.write('"'); writer.write(values[i].toString().replace("\"", "\"\"")); writer.write('"');
                }
            }
            writer.write('\n'); count++;
        }
        /**
         * Закрывает файл и дописывает содержимое буфера.
         *
         * @throws IOException при ошибке закрытия
         */
        @Override
        public void close() throws IOException { writer.close(); }
    }
}
