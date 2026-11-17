package ru.energosbyt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Команды создания схемы, генерации CSV, загрузки данных и очистки таблиц.
 */
public final class DataTools {
    /** Список таблиц для загрузки и очистки; измерения загружаются раньше ссылающихся на них фактов. */
    public static final List<String> TABLES = List.of("dim_realty", "dim_gauges", "dim_tariffs", "dim_users",
            "fact_gauge_bindings", "fact_meter_readings", "fact_invoice");
    private DataTools() { }
    /**
     * Выполняет {@code schema}, {@code gen}, {@code load}, {@code teardown} или проверку готовности {@code
     * ping}.
     * Перед загрузкой проверяются наличие всех CSV и пустота всех таблиц. При сбое уже вставленные строки не
     * откатываются; повторная загрузка требует очистки.
     * Команда очистки удаляет таблицы из списка TABLES, но сохраняет базу и CSV на хосте.
     *
     * @param args имя команды; второй аргумент задаёт каталог CSV для генерации или загрузки
     * @throws Exception при ошибке файловой системы, HTTP или выполнения SQL
     */
    public static void run(String[] args) throws Exception {
        long start = System.nanoTime();
        if (args[0].equals("ping")) {
            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + ClickHouse.required("APP_PORT") + "/ping")).timeout(java.time.Duration.ofSeconds(2)).GET().build();
            var response = java.net.http.HttpClient.newHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200) throw new IllegalStateException("Application not ready");
        } else if (args[0].equals("gen")) {
            DataGenerator.generate(args.length > 1 ? Path.of(args[1]) : Path.of("/data"));
        } else {
            ClickHouse ch = new ClickHouse();
            switch (args[0]) {
                case "schema" -> {
                    try (var stream = DataTools.class.getResourceAsStream("/schema.sql")) {
                        if (stream == null) throw new IllegalStateException("Missing schema.sql");
                        String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("{{database}}", ch.database());
                        for (String statement : sql.split(";")) if (!statement.isBlank()) ch.system(statement);
                    }
                }
                case "load" -> {
                    Path directory = args.length > 1 ? Path.of(args[1]) : Path.of("/data");
                    for (String table : TABLES) {
                        if (!Files.isRegularFile(directory.resolve(table + ".csv"))) throw new IllegalStateException("Missing CSV: " + table);
                        long count = ch.rows("SELECT count() AS n FROM " + table, Map.of()).get(0).get("n").asLong();
                        if (count != 0) throw new IllegalStateException("Load requires empty tables. Run make teardown and make setup; nonempty: " + table);
                    }
                    for (String table : TABLES) {
                        ch.load(table, directory.resolve(table + ".csv"));
                        System.out.println("Loaded " + table);
                    }
                }
                case "teardown" -> {
                    for (String table : TABLES) ch.system("DROP TABLE IF EXISTS " + ch.database() + "." + table + " SYNC");
                }
                default -> throw new IllegalArgumentException("Commands: schema, gen, load, teardown");
            }
        }
        System.out.printf("%s completed in %.2f s%n", args[0], (System.nanoTime() - start) / 1e9);
    }
}
